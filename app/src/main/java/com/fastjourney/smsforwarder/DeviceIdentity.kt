package com.fastjourney.smsforwarder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceIdentity {

    /**
     * Prefer the hardware serial (same value as `adb devices`), then fall back to ANDROID_ID.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun deviceId(context: Context): String {
        readSystemProperty("ro.serialno")
            ?.takeIf { isUsableSerial(it) }
            ?.let { return it }

        try {
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
            if (isUsableSerial(serial)) return serial
        } catch (_: SecurityException) {
            // READ_PHONE_STATE / privileged serial not granted
        }

        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
    }

    fun deviceName(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }
    }

    private fun isUsableSerial(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val v = value.trim()
        return !v.equals("unknown", ignoreCase = true) && v != "0"
    }

    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String)?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
