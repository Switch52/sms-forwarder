package com.fastjourney.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)

    val deviceId: String = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID
    ) ?: "unknown"

    var webhookUrl: String
        get() = prefs.getString(
            "webhook_url",
            "https://ejoin-sms-webhook-staging.fastjourney.shop/api/sms-webhook",
        ) ?: "https://ejoin-sms-webhook-staging.fastjourney.shop/api/sms-webhook"
        set(value) = prefs.edit().putString("webhook_url", value).apply()

    /** accounts-api POST /sms-devices/heartbeat */
    var heartbeatUrl: String
        get() = prefs.getString("heartbeat_url", "") ?: ""
        set(value) = prefs.edit().putString("heartbeat_url", value).apply()

    var sim1Number: String
        get() = prefs.getString("sim1_number", "") ?: ""
        set(value) = prefs.edit().putString("sim1_number", value).apply()

    var sim2Number: String
        get() = prefs.getString("sim2_number", "") ?: ""
        set(value) = prefs.edit().putString("sim2_number", value).apply()

    var authUsername: String
        get() = prefs.getString("auth_username", "") ?: ""
        set(value) = prefs.edit().putString("auth_username", value).apply()

    var authPassword: String
        get() = prefs.getString("auth_password", "") ?: ""
        set(value) = prefs.edit().putString("auth_password", value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    fun getNumberForSim(simIndex: Int): String {
        return when (simIndex) {
            0 -> sim1Number
            1 -> sim2Number
            else -> sim1Number
        }
    }

    fun hasAuth(): Boolean = authUsername.isNotBlank() && authPassword.isNotBlank()

    fun isConfigured(): Boolean {
        return webhookUrl.isNotBlank() && (sim1Number.isNotBlank() || sim2Number.isNotBlank())
    }
}
