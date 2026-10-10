package com.fastjourney.smsforwarder

import android.content.Context
import android.content.SharedPreferences

class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)

    /** Hardware serial when readable (matches `adb devices`), else ANDROID_ID. */
    val deviceId: String = DeviceIdentity.deviceId(context)

    val deviceName: String = DeviceIdentity.deviceName()

    var webhookUrl: String
        get() = prefs.getString("webhook_url", null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WEBHOOK_URL
        set(value) {
            prefs.edit().putString("webhook_url", value).commit()
        }

    /** accounts-api POST /sms-devices/heartbeat */
    var heartbeatUrl: String
        get() = prefs.getString("heartbeat_url", null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HEARTBEAT_URL
        set(value) {
            prefs.edit().putString("heartbeat_url", value).commit()
        }

    var sim1Number: String
        get() = prefs.getString("sim1_number", "") ?: ""
        set(value) {
            prefs.edit().putString("sim1_number", value).commit()
        }

    var sim2Number: String
        get() = prefs.getString("sim2_number", "") ?: ""
        set(value) {
            prefs.edit().putString("sim2_number", value).commit()
        }

    var authUsername: String
        get() = prefs.getString("auth_username", "") ?: ""
        set(value) {
            prefs.edit().putString("auth_username", value).commit()
        }

    /** accounts-api x-api-key (Auth Password field). */
    var authPassword: String
        get() = prefs.getString("auth_password", null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_API_KEY
        set(value) {
            prefs.edit().putString("auth_password", value).commit()
        }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) {
            prefs.edit().putBoolean("service_enabled", value).commit()
        }

    /** When true, local UI cannot change webhook/SIMs/API key — dashboard is source of truth. */
    var handsOff: Boolean
        get() = prefs.getBoolean("hands_off", false)
        set(value) {
            // commit() so UI refresh after heartbeat sees the value immediately (apply() races).
            prefs.edit().putBoolean("hands_off", value).commit()
        }

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

    /**
     * Learned MSISDN for a carrier label (e.g. "etisalat") from the first SMS we saw on that SIM.
     * Used when Android leaves SubscriptionInfo.number empty (common on Etisalat / e&).
     */
    fun getLearnedNumberForCarrier(carrierKey: String): String? {
        if (carrierKey.isBlank()) return null
        return prefs.getString(carrierPrefKey(carrierKey), null)?.takeIf { it.isNotBlank() }
    }

    fun setLearnedNumberForCarrier(carrierKey: String, number: String) {
        if (carrierKey.isBlank() || number.isBlank()) return
        prefs.edit().putString(carrierPrefKey(carrierKey), number.trim()).commit()
    }

    /** Drop carrier→MSISDN memory so the next SMS re-learns from current SIM config. */
    fun clearLearnedCarrierNumbers() {
        val editor = prefs.edit()
        var removed = 0
        for (key in prefs.all.keys) {
            if (key.startsWith("learned_carrier_")) {
                editor.remove(key)
                removed++
            }
        }
        if (removed > 0) editor.commit()
    }

    private fun carrierPrefKey(carrierKey: String): String =
        "learned_carrier_${carrierKey.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"

    companion object {
        const val DEFAULT_WEBHOOK_URL =
            "https://ejoin-sms-webhook-staging.fastjourney.shop/api/sms-webhook"
        const val DEFAULT_HEARTBEAT_URL =
            "https://visaflow-backend.fastjourney.shop/sms-devices/heartbeat"
        const val DEFAULT_API_KEY = "test-api-key-12345"
    }
}
