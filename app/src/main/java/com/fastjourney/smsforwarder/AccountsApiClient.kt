package com.fastjourney.smsforwarder

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AccountsApiClient {

    fun devicesBaseUrl(heartbeatUrl: String): String? {
        val trimmed = heartbeatUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return when {
            trimmed.endsWith("/sms-devices/heartbeat") ->
                trimmed.removeSuffix("/heartbeat")
            trimmed.endsWith("/heartbeat") ->
                trimmed.removeSuffix("/heartbeat")
            else -> trimmed.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { null }
        }
    }

    fun postMessageLog(
        heartbeatUrl: String,
        apiKey: String,
        deviceId: String,
        simSlot: Int,
        simNumber: String?,
        sender: String,
        body: String,
        httpStatus: Int?,
    ) {
        val base = devicesBaseUrl(heartbeatUrl) ?: return
        if (apiKey.isBlank()) return

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("simSlot", simSlot)
            put("simNumber", simNumber?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("sender", sender)
            put("body", body)
            put("httpStatus", httpStatus ?: JSONObject.NULL)
            put("forwardedAt", System.currentTimeMillis())
        }.toString()

        val url = URL("$base/messages")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    fun postError(
        heartbeatUrl: String,
        apiKey: String,
        deviceId: String,
        code: String,
        message: String,
        simSlot: Int?,
        configuredNumber: String?,
        detectedNumber: String?,
    ) {
        val base = devicesBaseUrl(heartbeatUrl) ?: return
        if (apiKey.isBlank()) return

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("code", code)
            put("message", message)
            put("simSlot", simSlot ?: JSONObject.NULL)
            put("configuredNumber", configuredNumber?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("detectedNumber", detectedNumber?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("recordedAt", System.currentTimeMillis())
        }.toString()

        val url = URL("$base/errors")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
