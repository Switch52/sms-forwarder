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
import android.content.pm.PackageManager
import android.os.Build
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ForwarderService : Service() {

    private lateinit var executor: ExecutorService
    private lateinit var wakeLock: PowerManager.WakeLock
    private val connectivityReceiver = ConnectivityReceiver()
    private var heartbeatScheduler: ScheduledExecutorService? = null
    private val heartbeatRunning = AtomicBoolean(false)

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
        scheduleWatchdog(this)
        startHeartbeatLoop()

        registerReceiver(
            connectivityReceiver,
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        )

        FileLog.log(this, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FLUSH_QUEUE -> executor.execute { flushQueue() }
            ACTION_HEARTBEAT -> executor.execute { sendHeartbeat() }
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
        stopHeartbeatLoop()
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

    private fun startHeartbeatLoop() {
        if (heartbeatScheduler != null) return
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleAtFixedRate(
                {
                    if (Config(this).serviceEnabled) {
                        sendHeartbeat()
                    }
                },
                5,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
        // Immediate first beat
        executor.execute { sendHeartbeat() }
    }

    private fun stopHeartbeatLoop() {
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
    }

    private fun readAppVersion(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val name = info.versionName?.takeIf { it.isNotBlank() } ?: "?"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$name ($code)"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun sendHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) return
        try {
            val config = Config(this)
            val url = config.heartbeatUrl.trim()
            if (url.isBlank()) return

            val payload = JSONObject().apply {
                put("deviceId", config.deviceId)
                put("deviceName", config.deviceName)
                put("sim1Number", config.sim1Number.ifBlank { JSONObject.NULL })
                put("sim2Number", config.sim2Number.ifBlank { JSONObject.NULL })
                put("appVersion", readAppVersion())
            }.toString()

            try {
                val code = doPost(url, payload, config, forHeartbeat = true)
                if (code in 200..299) {
                    FileLog.log(this, "-> HEARTBEAT OK (HTTP $code)")
                } else {
                    FileLog.log(this, "!! HEARTBEAT HTTP $code")
                }
            } catch (e: Exception) {
                FileLog.log(this, "!! HEARTBEAT error: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            heartbeatRunning.set(false)
        }
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
                    reportForwardedSms(config, payload, code)
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
                } else {
                    reportForwardedSms(config, payload, code)
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

    private fun reportForwardedSms(config: Config, payload: String, httpStatus: Int) {
        try {
            val root = JSONObject(payload)
            val data = root.optJSONObject("payload") ?: root
            val sender = data.optString("sender", "?")
            val body = data.optString("message", data.optString("body", ""))
            val simSlot = data.optInt("simNumber", 1).coerceIn(1, 2)
            val simNumber = config.getNumberForSim(simSlot - 1).ifBlank { null }
            AccountsApiClient.postMessageLog(
                heartbeatUrl = config.heartbeatUrl,
                apiKey = config.authPassword,
                deviceId = config.deviceId,
                simSlot = simSlot,
                simNumber = simNumber,
                sender = sender,
                body = body,
                httpStatus = httpStatus,
            )
        } catch (e: Exception) {
            FileLog.log(this, "!! SMS log error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun doPost(
        webhookUrl: String,
        payload: String,
        config: Config,
        forHeartbeat: Boolean = false,
    ): Int {
        val url = URL(webhookUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true

        if (forHeartbeat && config.authPassword.isNotBlank()) {
            // accounts-api requireApiKey expects x-api-key; reuse Auth Password as the API key.
            conn.setRequestProperty("x-api-key", config.authPassword)
        }

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

    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_WEBHOOK_URL = "webhook_url"
        const val EXTRA_PAYLOAD = "payload"
        const val ACTION_LOG_UPDATED = "com.fastjourney.smsforwarder.LOG_UPDATED"
        const val ACTION_FLUSH_QUEUE = "com.fastjourney.smsforwarder.FLUSH_QUEUE"
        const val ACTION_HEARTBEAT = "com.fastjourney.smsforwarder.HEARTBEAT"
        const val ACTION_WATCHDOG = "com.fastjourney.smsforwarder.WATCHDOG"
        private const val HEARTBEAT_INTERVAL_SECONDS = 60L
        private const val WATCHDOG_INTERVAL_MS = 90_000L
        private const val WATCHDOG_REQUEST_CODE = 998
        private const val TAG = "SmsForwarder"

        /** Exact one-shot alarm that BootReceiver re-arms on every fire (setRepeating is unreliable). */
        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pi = PendingIntent.getBroadcast(
                context, WATCHDOG_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

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
