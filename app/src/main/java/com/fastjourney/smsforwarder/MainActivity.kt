package com.fastjourney.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var webhookUrlInput: TextInputEditText
    private lateinit var heartbeatUrlInput: TextInputEditText
    private lateinit var sim1Input: TextInputEditText
    private lateinit var sim2Input: TextInputEditText
    private lateinit var authUsernameInput: TextInputEditText
    private lateinit var authPasswordInput: TextInputEditText
    private lateinit var toggleButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var permissionWarning: MaterialCardView
    private lateinit var permissionDetails: TextView
    private lateinit var grantPermissions: MaterialButton
    private lateinit var keepAliveWarning: MaterialCardView
    private lateinit var keepAliveDetails: TextView
    private lateinit var fixKeepAlive: MaterialButton
    private lateinit var openOemSettings: MaterialButton
    private lateinit var ackOemDone: MaterialButton
    private lateinit var ackRecentsDone: MaterialButton

    private val uiHandler = Handler(Looper.getMainLooper())
    private val configPoll = object : Runnable {
        override fun run() {
            applyRemoteConfigToUi()
            uiHandler.postDelayed(this, CONFIG_POLL_MS)
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyRemoteConfigToUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = Config(this)

        webhookUrlInput = findViewById(R.id.webhookUrl)
        heartbeatUrlInput = findViewById(R.id.heartbeatUrl)
        sim1Input = findViewById(R.id.sim1Number)
        sim2Input = findViewById(R.id.sim2Number)
        authUsernameInput = findViewById(R.id.authUsername)
        authPasswordInput = findViewById(R.id.authPassword)
        toggleButton = findViewById(R.id.toggleService)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        permissionWarning = findViewById(R.id.permissionWarning)
        permissionDetails = findViewById(R.id.permissionDetails)
        grantPermissions = findViewById(R.id.grantPermissions)
        keepAliveWarning = findViewById(R.id.keepAliveWarning)
        keepAliveDetails = findViewById(R.id.keepAliveDetails)
        fixKeepAlive = findViewById(R.id.fixKeepAlive)
        openOemSettings = findViewById(R.id.openOemSettings)
        ackOemDone = findViewById(R.id.ackOemDone)
        ackRecentsDone = findViewById(R.id.ackRecentsDone)

        handleIntentExtras(intent)

        webhookUrlInput.setText(config.webhookUrl)
        heartbeatUrlInput.setText(config.heartbeatUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)
        authUsernameInput.setText(config.authUsername)
        authPasswordInput.setText(config.authPassword)

        toggleButton.setOnClickListener { toggleService() }
        grantPermissions.setOnClickListener {
            requestPermissions()
            KeepAliveHelper.runWizard(this)
        }
        fixKeepAlive.setOnClickListener {
            KeepAliveHelper.runWizard(this)
            Toast.makeText(this, "Allow Unrestricted battery, then OEM auto-start", Toast.LENGTH_LONG).show()
        }
        openOemSettings.setOnClickListener {
            KeepAliveHelper.openOemAutostartSettings(this)
        }
        ackOemDone.setOnClickListener {
            KeepAliveHelper.setOemAcked(this)
            updateUI()
            Toast.makeText(this, "Auto-start marked done", Toast.LENGTH_SHORT).show()
        }
        ackRecentsDone.setOnClickListener {
            KeepAliveHelper.setRecentsAcked(this)
            updateUI()
            Toast.makeText(this, "Recents lock marked done", Toast.LENGTH_SHORT).show()
        }

        requestPermissions()
        if (!KeepAliveHelper.isSetupComplete(this)) {
            // Auto-open battery / OEM screens when still incomplete
            KeepAliveHelper.runWizard(this)
        }
        updateUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
        webhookUrlInput.setText(config.webhookUrl)
        heartbeatUrlInput.setText(config.heartbeatUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            logReceiver,
            IntentFilter(ForwarderService.ACTION_LOG_UPDATED),
            Context.RECEIVER_NOT_EXPORTED
        )
        applyRemoteConfigToUi()
        uiHandler.removeCallbacks(configPoll)
        uiHandler.postDelayed(configPoll, CONFIG_POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(configPoll)
        unregisterReceiver(logReceiver)
        saveConfig()
    }

    private fun applyRemoteConfigToUi() {
        if (!::webhookUrlInput.isInitialized) return
        config = Config(this)
        webhookUrlInput.setText(config.webhookUrl)
        heartbeatUrlInput.setText(config.heartbeatUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)
        authPasswordInput.setText(config.authPassword)
        refreshLog()
        updateUI()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUI()
    }

    private fun saveConfig() {
        if (config.handsOff) return
        config.webhookUrl = webhookUrlInput.text.toString().trim()
        config.heartbeatUrl = heartbeatUrlInput.text.toString().trim()
        config.sim1Number = sim1Input.text.toString().trim()
        config.sim2Number = sim2Input.text.toString().trim()
        config.authUsername = authUsernameInput.text.toString().trim()
        config.authPassword = authPasswordInput.text.toString().trim()
    }

    private fun applyHandsOffUi() {
        val locked = config.handsOff
        webhookUrlInput.isEnabled = !locked
        heartbeatUrlInput.isEnabled = !locked
        sim1Input.isEnabled = !locked
        sim2Input.isEnabled = !locked
        authUsernameInput.isEnabled = !locked
        authPasswordInput.isEnabled = !locked
        toggleButton.isEnabled = !locked
    }

    private fun toggleService() {
        if (config.handsOff) {
            Toast.makeText(this, "Hands-off mode: change settings from the dashboard", Toast.LENGTH_LONG).show()
            return
        }
        saveConfig()

        if (config.serviceEnabled) {
            config.serviceEnabled = false
            ForwarderService.stop(this)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (!config.isConfigured()) {
                Toast.makeText(this, "Enter webhook URL and at least one SIM number", Toast.LENGTH_LONG).show()
                return
            }
            if (!KeepAliveHelper.isSetupComplete(this)) {
                Toast.makeText(
                    this,
                    "Fix KEEP ALIVE checklist first — phone will kill the service otherwise",
                    Toast.LENGTH_LONG
                ).show()
                KeepAliveHelper.runWizard(this)
            }
            config.serviceEnabled = true
            ForwarderService.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    private fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()

        val required = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
            required.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        for (p in required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p)
            }
        }

        return missing
    }

    private fun updateUI() {
        config = Config(this)
        applyHandsOffUi()
        val queued = MessageQueue.size(this)
        val missing = getMissingPermissions()
        val batteryOptimized = KeepAliveHelper.isBatteryOptimized(this)
        val keepAliveIncomplete = !KeepAliveHelper.isSetupComplete(this)

        if (missing.isNotEmpty()) {
            permissionWarning.visibility = View.VISIBLE
            val names = missing.map { it.substringAfterLast(".") }
            permissionDetails.text = "Missing permissions: ${names.joinToString(", ")}"
        } else {
            permissionWarning.visibility = View.GONE
        }

        if (keepAliveIncomplete) {
            keepAliveWarning.visibility = View.VISIBLE
            keepAliveDetails.text = KeepAliveHelper.checklistText(this)
            val oem = KeepAliveHelper.isAggressiveOem()
            openOemSettings.visibility = if (oem) View.VISIBLE else View.GONE
            ackOemDone.visibility =
                if (oem && !KeepAliveHelper.isOemAcked(this)) View.VISIBLE else View.GONE
            ackRecentsDone.visibility =
                if (oem && !KeepAliveHelper.isRecentsAcked(this)) View.VISIBLE else View.GONE
        } else {
            keepAliveWarning.visibility = View.GONE
        }

        if (config.serviceEnabled) {
            toggleButton.text = if (config.handsOff) "Hands-off (dashboard managed)" else "Stop Service"
            statusText.text = buildString {
                append("Service running")
                if (config.handsOff) append("\nHANDS-OFF: settings locked — edit from dashboard")
                append("\nDevice: ${config.deviceName}")
                append("\nDevice ID: ${config.deviceId}")
                append("\nWebhook: ${config.webhookUrl}")
                append("\nHeartbeat: ${config.heartbeatUrl.ifBlank { "(not set)" }}")
                append("\nSIM 1: ${config.sim1Number.ifBlank { "(not set)" }}")
                append("\nSIM 2: ${config.sim2Number.ifBlank { "(not set)" }}")
                if (queued > 0) append("\nQueued: $queued messages pending")
                if (missing.isNotEmpty()) append("\nWARNING: ${missing.size} permissions missing!")
                if (batteryOptimized) append("\nWARNING: Battery optimization will kill this app!")
                if (keepAliveIncomplete) append("\nWARNING: Keep-alive setup incomplete!")
            }
        } else {
            toggleButton.text = "Start Service"
            statusText.text = if (config.handsOff) {
                "Service stopped (hands-off — start from dashboard restart command after enabling locally once)"
            } else {
                "Service stopped"
            }
        }
    }

    private fun refreshLog() {
        val log = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
            .getString("log", null)
        logText.text = log ?: "No messages forwarded yet"
        updateUI()
    }

    private fun handleIntentExtras(intent: Intent?) {
        intent ?: return
        val url = intent.getStringExtra("webhook_url")
        val heartbeatUrl = intent.getStringExtra("heartbeat_url")
        val sim1 = intent.getStringExtra("sim1")
        val sim2 = intent.getStringExtra("sim2")
        val apiKey = intent.getStringExtra("api_key")
        val deviceIdOverride = intent.getStringExtra("device_id")
        val autoStart = intent.getBooleanExtra("start", false)

        if (url != null) config.webhookUrl = url
        if (heartbeatUrl != null) config.heartbeatUrl = heartbeatUrl
        if (sim1 != null) config.sim1Number = sim1
        if (sim2 != null) config.sim2Number = sim2
        if (apiKey != null) config.authPassword = apiKey
        if (!deviceIdOverride.isNullOrBlank()) {
            DeviceIdentity.setDeviceIdOverride(this, deviceIdOverride)
            config = Config(this)
        }

        if (autoStart && config.isConfigured() && !config.serviceEnabled) {
            config.serviceEnabled = true
            ForwarderService.start(this)
        }
    }

    private fun requestPermissions() {
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    companion object {
        private const val CONFIG_POLL_MS = 2_000L
    }
}
