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
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var webhookUrlInput: TextInputEditText
    private lateinit var sim1Input: TextInputEditText
    private lateinit var sim2Input: TextInputEditText
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
        toggleButton = findViewById(R.id.toggleService)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        handleIntentExtras(intent)

        webhookUrlInput.setText(config.webhookUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)

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
    }

    private fun toggleService() {
        saveConfig()

        if (config.serviceEnabled) {
            config.serviceEnabled = false
            ForwarderService.stop(this)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (!config.isConfigured()) {
                Toast.makeText(this, "Enter webhook URL and at least SIM 1 number", Toast.LENGTH_LONG).show()
                return
            }
            config.serviceEnabled = true
            ForwarderService.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    private fun updateUI() {
        if (config.serviceEnabled) {
            toggleButton.text = "Stop Service"
            statusText.text = "Service running\nDevice ID: ${config.deviceId}\nWebhook: ${config.webhookUrl}\nSIM 1: ${config.sim1Number}\nSIM 2: ${config.sim2Number.ifBlank { "(not set)" }}"
        } else {
            toggleButton.text = "Start Service"
            statusText.text = "Service stopped"
        }
    }

    private fun refreshLog() {
        val log = getSharedPreferences("sms_forwarder", MODE_PRIVATE)
            .getString("log", null)
        logText.text = log ?: "No messages forwarded yet"
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
