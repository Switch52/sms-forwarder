package com.fastjourney.smsforwarder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ForwarderService : Service() {

    private lateinit var executor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsForwarder::ServiceLock"
        ).apply { acquire() }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val webhookUrl = intent?.getStringExtra(EXTRA_WEBHOOK_URL)
        val payload = intent?.getStringExtra(EXTRA_PAYLOAD)

        if (webhookUrl != null && payload != null) {
            executor.execute { postWebhook(webhookUrl, payload) }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        executor.shutdownNow()

        val config = Config(this)
        if (config.serviceEnabled) {
            Log.w(TAG, "Service destroyed while enabled — scheduling restart")
            scheduleRestart()
        }
        super.onDestroy()
    }

    private fun scheduleRestart() {
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = "com.fastjourney.smsforwarder.RESTART"
        }
        val pi = PendingIntent.getBroadcast(
            this, 999, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5000,
            pi
        )
    }

    private fun scheduleWatchdog() {
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = "com.fastjourney.smsforwarder.WATCHDOG"
        }
        val pi = PendingIntent.getBroadcast(
            this, 998, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000,
            5 * 60_000,
            pi
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Forwarder",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps SMS forwarding active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Forwarder")
            .setContentText("Forwarding incoming SMS to webhook")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun postWebhook(webhookUrl: String, payload: String) {
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            try {
                val url = URL(webhookUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    Log.d(TAG, "Webhook sent successfully (HTTP $code)")
                    appendLog("✓ Forwarded (HTTP $code)")
                    return
                }

                Log.w(TAG, "Webhook returned HTTP $code, attempt ${attempts + 1}")
                attempts++
            } catch (e: Exception) {
                Log.e(TAG, "Webhook failed, attempt ${attempts + 1}", e)
                attempts++
                if (attempts < maxAttempts) Thread.sleep(2000L * attempts)
            }
        }

        appendLog("✗ Failed after $maxAttempts attempts")
    }

    private fun appendLog(entry: String) {
        val prefs = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
        val existing = prefs.getString("log", "") ?: ""
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.US
        ).format(java.util.Date())
        val newLog = "[$timestamp] $entry\n$existing"
        val trimmed = newLog.lines().take(50).joinToString("\n")
        prefs.edit().putString("log", trimmed).apply()

        sendBroadcast(Intent(ACTION_LOG_UPDATED))
    }

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_WEBHOOK_URL = "webhook_url"
        const val EXTRA_PAYLOAD = "payload"
        const val ACTION_LOG_UPDATED = "com.fastjourney.smsforwarder.LOG_UPDATED"
        private const val TAG = "SmsForwarder"

        fun enqueueWebhook(context: Context, webhookUrl: String, payload: String) {
            val intent = Intent(context, ForwarderService::class.java).apply {
                putExtra(EXTRA_WEBHOOK_URL, webhookUrl)
                putExtra(EXTRA_PAYLOAD, payload)
            }
            context.startForegroundService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, ForwarderService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwarderService::class.java))
        }
    }
}
