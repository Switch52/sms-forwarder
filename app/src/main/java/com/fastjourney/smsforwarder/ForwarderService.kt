package com.fastjourney.smsforwarder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ForwarderService : Service() {

    private lateinit var executor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private val connectivityReceiver = ConnectivityReceiver()

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

        registerReceiver(
            connectivityReceiver,
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        )

        FileLog.log(this, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FLUSH_QUEUE -> executor.execute { flushQueue() }
            else -> {
                val webhookUrl = intent?.getStringExtra(EXTRA_WEBHOOK_URL)
                val payload = intent?.getStringExtra(EXTRA_PAYLOAD)
                if (webhookUrl != null && payload != null) {
                    executor.execute { postWebhook(webhookUrl, payload) }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(connectivityReceiver) } catch (_: Exception) {}
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        executor.shutdownNow()

        val config = Config(this)
        if (config.serviceEnabled) {
            Log.w(TAG, "Service destroyed while enabled — scheduling restart")
            scheduleRestart()
        }

        FileLog.log(this, "Service stopped")
        super.onDestroy()
    }

    private fun postWebhook(webhookUrl: String, payload: String) {
        val config = Config(this)
        var attempts = 0
        val maxAttempts = 3

        val sender = try {
            JSONObject(payload).getJSONObject("payload").optString("sender", "?")
        } catch (_: Exception) { "?" }
        val simNum = try {
            JSONObject(payload).getJSONObject("payload").optInt("simNumber", 0)
        } catch (_: Exception) { 0 }

        FileLog.log(this, ">> POST attempt to $webhookUrl")

        while (attempts < maxAttempts) {
            try {
                val code = doPost(webhookUrl, payload, config)

                if (code in 200..299) {
                    FileLog.log(this, "-> SENT SIM$simNum from $sender (HTTP $code)")
                    flushQueue()
                    return
                }

                FileLog.log(this, "!! Webhook HTTP $code, attempt ${attempts + 1}/$maxAttempts")
                attempts++
            } catch (e: Exception) {
                FileLog.log(this, "!! Webhook error: ${e.javaClass.simpleName}: ${e.message}, attempt ${attempts + 1}/$maxAttempts")
                attempts++
                if (attempts < maxAttempts) Thread.sleep(2000L * attempts)
            }
        }

        MessageQueue.enqueue(this, payload)
        val queued = MessageQueue.size(this)
        FileLog.log(this, "-> QUEUED SIM$simNum from $sender ($queued pending)")
    }

    private fun flushQueue() {
        val config = Config(this)
        val pending = MessageQueue.drainAll(this)
        if (pending.isEmpty()) return

        FileLog.log(this, "Flushing ${pending.size} queued messages")

        for (payload in pending) {
            try {
                val code = doPost(config.webhookUrl, payload, config)
                if (code !in 200..299) {
                    MessageQueue.enqueue(this, payload)
                }
            } catch (_: Exception) {
                MessageQueue.enqueue(this, payload)
            }
        }

        val remaining = MessageQueue.size(this)
        if (remaining > 0) {
            FileLog.log(this, "Queue flush incomplete, $remaining still pending")
        }
    }

    private fun doPost(webhookUrl: String, payload: String, config: Config): Int {
        val url = URL(webhookUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true

        if (config.hasAuth()) {
            val creds = "${config.authUsername}:${config.authPassword}"
            val encoded = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload) }
        val code = conn.responseCode
        conn.disconnect()
        return code
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

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_WEBHOOK_URL = "webhook_url"
        const val EXTRA_PAYLOAD = "payload"
        const val ACTION_LOG_UPDATED = "com.fastjourney.smsforwarder.LOG_UPDATED"
        const val ACTION_FLUSH_QUEUE = "com.fastjourney.smsforwarder.FLUSH_QUEUE"
        private const val TAG = "SmsForwarder"

        fun enqueueWebhook(context: Context, webhookUrl: String, payload: String) {
            val intent = Intent(context, ForwarderService::class.java).apply {
                putExtra(EXTRA_WEBHOOK_URL, webhookUrl)
                putExtra(EXTRA_PAYLOAD, payload)
            }
            context.startForegroundService(intent)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ForwarderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwarderService::class.java))
        }

        fun flushQueue(context: Context) {
            val intent = Intent(context, ForwarderService::class.java).apply {
                action = ACTION_FLUSH_QUEUE
            }
            context.startForegroundService(intent)
        }
    }
}
