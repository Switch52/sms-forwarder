package com.fastjourney.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var webhookUrlInput: TextInputEditText
    private lateinit var sim1Input: TextInputEditText
    private lateinit var sim2Input: TextInputEditText
    private lateinit var authUsernameInput: TextInputEditText
    private lateinit var authPasswordInput: TextInputEditText
    private lateinit var otpFilterSwitch: MaterialSwitch
    private lateinit var heartbeatSwitch: MaterialSwitch
    private lateinit var toggleButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = Config(this)

        webhookUrlInput = findViewById(R.id.webhookUrl)
        sim1Input = findViewById(R.id.sim1Number)
        sim2Input = findViewById(R.id.sim2Number)
        authUsernameInput = findViewById(R.id.authUsername)
        authPasswordInput = findViewById(R.id.authPassword)
        otpFilterSwitch = findViewById(R.id.otpFilter)
        heartbeatSwitch = findViewById(R.id.heartbeat)
        toggleButton = findViewById(R.id.toggleService)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        handleIntentExtras(intent)

        webhookUrlInput.setText(config.webhookUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)
        authUsernameInput.setText(config.authUsername)
        authPasswordInput.setText(config.authPassword)
        otpFilterSwitch.isChecked = config.otpFilterEnabled
        heartbeatSwitch.isChecked = config.heartbeatEnabled

        otpFilterSwitch.setOnCheckedChangeListener { _, checked -> config.otpFilterEnabled = checked }
        heartbeatSwitch.setOnCheckedChangeListener { _, checked -> config.heartbeatEnabled = checked }
        toggleButton.setOnClickListener { toggleService() }

        requestPermissions()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            logReceiver,
            IntentFilter(ForwarderService.ACTION_LOG_UPDATED),
            Context.RECEIVER_NOT_EXPORTED
        )
        refreshLog()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
        saveConfig()
    }

    private fun saveConfig() {
        config.webhookUrl = webhookUrlInput.text.toString().trim()
        config.sim1Number = sim1Input.text.toString().trim()
        config.sim2Number = sim2Input.text.toString().trim()
        config.authUsername = authUsernameInput.text.toString().trim()
        config.authPassword = authPasswordInput.text.toString().trim()
    }

    private fun toggleService() {
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
            config.serviceEnabled = true
            ForwarderService.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    private fun updateUI() {
        val queued = MessageQueue.size(this)
        if (config.serviceEnabled) {
            toggleButton.text = "Stop Service"
            statusText.text = buildString {
                append("Service running")
                append("\nDevice ID: ${config.deviceId}")
                append("\nWebhook: ${config.webhookUrl}")
                append("\nSIM 1: ${config.sim1Number.ifBlank { "(not set)" }}")
                append("\nSIM 2: ${config.sim2Number.ifBlank { "(not set)" }}")
                if (queued > 0) append("\nQueued: $queued messages pending")
            }
        } else {
            toggleButton.text = "Start Service"
            statusText.text = "Service stopped"
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
        val sim1 = intent.getStringExtra("sim1")
        val sim2 = intent.getStringExtra("sim2")
        val autoStart = intent.getBooleanExtra("start", false)

        if (url != null) config.webhookUrl = url
        if (sim1 != null) config.sim1Number = sim1
        if (sim2 != null) config.sim2Number = sim2

        if (autoStart && config.isConfigured() && !config.serviceEnabled) {
            config.serviceEnabled = true
            ForwarderService.start(this)
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )

        for (p in permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }
}
