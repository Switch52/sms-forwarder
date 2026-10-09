package com.fastjourney.smsforwarder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
    private lateinit var detectSimsButton: MaterialButton
    private lateinit var fetchUssdButton: MaterialButton
    private lateinit var simDetectStatus: TextView
    private var ussdInProgress = false

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
        toggleButton = findViewById(R.id.toggleService)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        permissionWarning = findViewById(R.id.permissionWarning)
        permissionDetails = findViewById(R.id.permissionDetails)
        grantPermissions = findViewById(R.id.grantPermissions)
        detectSimsButton = findViewById(R.id.detectSims)
        fetchUssdButton = findViewById(R.id.fetchUssd)
        simDetectStatus = findViewById(R.id.simDetectStatus)

        handleIntentExtras(intent)

        webhookUrlInput.setText(config.webhookUrl)
        sim1Input.setText(config.sim1Number)
        sim2Input.setText(config.sim2Number)
        authUsernameInput.setText(config.authUsername)
        authPasswordInput.setText(config.authPassword)

        toggleButton.setOnClickListener { toggleService() }
        detectSimsButton.setOnClickListener { detectAndFillSims() }
        fetchUssdButton.setOnClickListener { fetchNumbersViaUssd() }
        grantPermissions.setOnClickListener {
            requestPermissions()
            requestBatteryOptimizationExemption()
        }

        requestPermissions()
        requestBatteryOptimizationExemption()
        updateUI()
        refreshSimDetectStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
        webhookUrlInput.setText(config.webhookUrl)
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
        refreshLog()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
        saveConfig()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUI()
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
            val mismatch = softValidateAgainstOs()
            if (mismatch != null) {
                Toast.makeText(this, mismatch, Toast.LENGTH_LONG).show()
            }
            config.serviceEnabled = true
            ForwarderService.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        updateUI()
        refreshSimDetectStatus()
    }

    /** Soft check only — never blocks start when OS MSISDN is missing. */
    private fun softValidateAgainstOs(): String? {
        val detected = SimInfo.detect(this)
        if (detected.isEmpty()) return null

        val issues = mutableListOf<String>()
        for (slot in 0..1) {
            val typed = if (slot == 0) config.sim1Number else config.sim2Number
            val os = SimInfo.numberForSlot(detected, slot)
            if (typed.isBlank() || os.isBlank()) continue
            if (!SimInfo.numbersMatch(typed, os)) {
                issues.add("SIM ${slot + 1}: typed $typed vs OS $os")
            }
        }
        return if (issues.isEmpty()) null
        else "Warning: number mismatch — ${issues.joinToString("; ")}"
    }

    private fun detectAndFillSims() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant Phone permission first", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }

        val detected = SimInfo.detect(this)
        if (detected.isEmpty()) {
            simDetectStatus.text = "No active SIMs found (or permission denied)."
            Toast.makeText(this, "No SIMs detected", Toast.LENGTH_SHORT).show()
            return
        }

        var filled = 0
        for (sim in detected) {
            if (sim.number.isBlank()) continue
            when (sim.slotIndex) {
                0 -> {
                    sim1Input.setText(sim.number)
                    filled++
                }
                1 -> {
                    sim2Input.setText(sim.number)
                    filled++
                }
            }
        }
        saveConfig()
        refreshSimDetectStatus()

        if (filled == 0) {
            Toast.makeText(
                this,
                "SIMs found but OS returned no phone numbers (common on prepaid). Try Fetch via USSD.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Filled $filled number(s) from the OS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchNumbersViaUssd() {
        if (ussdInProgress) {
            Toast.makeText(this, "USSD already running…", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant Phone + Call permissions first", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }

        ussdInProgress = true
        fetchUssdButton.isEnabled = false
        simDetectStatus.text = "Starting USSD on each SIM…"
        FileLog.log(this, ">> USSD number fetch started")

        UssdNumberFetcher.fetchForActiveSims(
            context = this,
            onProgress = { msg ->
                runOnUiThread {
                    simDetectStatus.text = msg
                    FileLog.log(this, ">> USSD: $msg")
                }
            },
            onFinished = { results ->
                runOnUiThread {
                    ussdInProgress = false
                    fetchUssdButton.isEnabled = true
                    var filled = 0
                    val lines = mutableListOf<String>()
                    for (r in results) {
                        FileLog.log(
                            this,
                            ">> USSD slot=${r.slotIndex + 1} code=${r.ussdCode} parsed=${r.parsedNumber} err=${r.error} raw=${r.rawResponse.take(80)}"
                        )
                        if (r.slotIndex < 0) {
                            lines.add(r.error ?: "error")
                            continue
                        }
                        if (r.parsedNumber != null) {
                            when (r.slotIndex) {
                                0 -> {
                                    sim1Input.setText(r.parsedNumber)
                                    filled++
                                }
                                1 -> {
                                    sim2Input.setText(r.parsedNumber)
                                    filled++
                                }
                            }
                            lines.add("Slot ${r.slotIndex + 1}: ${r.parsedNumber} (via ${r.ussdCode})")
                        } else {
                            val detail = r.error ?: r.rawResponse.ifBlank { "no number" }
                            lines.add("Slot ${r.slotIndex + 1}: failed — $detail")
                        }
                    }
                    saveConfig()
                    simDetectStatus.text = lines.joinToString("\n").ifBlank { "No USSD results." }
                    Toast.makeText(
                        this,
                        if (filled > 0) "Filled $filled number(s) via USSD"
                        else "USSD did not return numbers — enter manually",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshLog()
                }
            },
        )
    }

    private fun refreshSimDetectStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            simDetectStatus.text = "Need Phone permission to detect SIM numbers."
            return
        }

        val detected = SimInfo.detect(this)
        if (detected.isEmpty()) {
            simDetectStatus.text = "No active SIMs detected."
            return
        }

        simDetectStatus.text = detected.joinToString("\n") { sim ->
            val num = sim.number.ifBlank { "(not provided by carrier/OS)" }
            val carrier = sim.carrierName.ifBlank { sim.displayName }.ifBlank { "?" }
            val typed = if (sim.slotIndex == 0) config.sim1Number else config.sim2Number
            val match = when {
                sim.number.isBlank() || typed.isBlank() -> ""
                SimInfo.numbersMatch(typed, sim.number) -> " ✓ matches typed"
                else -> " ✗ differs from typed"
            }
            "Slot ${sim.slotIndex + 1} ($carrier): $num$match"
        }
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        if (!isBatteryOptimized()) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()

        val required = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        for (p in required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p)
            }
        }

        return missing
    }

    private fun updateUI() {
        val queued = MessageQueue.size(this)
        val missing = getMissingPermissions()
        val batteryOptimized = isBatteryOptimized()

        if (missing.isNotEmpty() || batteryOptimized) {
            permissionWarning.visibility = View.VISIBLE
            val parts = mutableListOf<String>()
            if (missing.isNotEmpty()) {
                val names = missing.map { it.substringAfterLast(".") }
                parts.add("Missing permissions: ${names.joinToString(", ")}")
            }
            if (batteryOptimized) {
                parts.add("Battery optimization is ON — the app WILL be killed in the background. Tap Grant Permissions to fix.")
            }
            permissionDetails.text = parts.joinToString("\n\n")
        } else {
            permissionWarning.visibility = View.GONE
        }

        if (config.serviceEnabled) {
            toggleButton.text = "Stop Service"
            statusText.text = buildString {
                append("Service running")
                append("\nDevice ID: ${config.deviceId}")
                append("\nWebhook: ${config.webhookUrl}")
                append("\nSIM 1: ${config.sim1Number.ifBlank { "(not set)" }}")
                append("\nSIM 2: ${config.sim2Number.ifBlank { "(not set)" }}")
                if (queued > 0) append("\nQueued: $queued messages pending")
                if (missing.isNotEmpty()) append("\nWARNING: ${missing.size} permissions missing!")
                if (batteryOptimized) append("\nWARNING: Battery optimization will kill this app!")
            }
        } else {
            toggleButton.text = "Start Service"
            statusText.text = "Service stopped"
        }

        refreshSimDetectStatus()
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
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }
}
