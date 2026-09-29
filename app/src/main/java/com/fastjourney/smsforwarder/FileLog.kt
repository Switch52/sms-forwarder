package com.fastjourney.smsforwarder

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLog {
    private const val TAG = "SmsForwarder"
    private const val FILE_NAME = "sms_forwarder.log"
    private const val MAX_LINES = 200
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(context: Context, entry: String) {
        val line = "[${fmt.format(Date())}] $entry"
        Log.d(TAG, entry)

        synchronized(this) {
            try {
                val file = logFile(context)
                val existing = if (file.exists()) file.readLines() else emptyList()
                val updated = (listOf(line) + existing).take(MAX_LINES)
                file.writeText(updated.joinToString("\n"))
            } catch (_: Exception) {}

            updatePrefsLog(context, line)
        }
        context.sendBroadcast(Intent(ForwarderService.ACTION_LOG_UPDATED))
    }

    fun read(context: Context): String {
        val file = logFile(context)
        return if (file.exists()) file.readText() else ""
    }

    private fun logFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun updatePrefsLog(context: Context, line: String) {
        val prefs = context.getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
        val existing = prefs.getString("log", "") ?: ""
        val newLog = "$line\n$existing"
        val trimmed = newLog.lines().take(50).joinToString("\n")
        prefs.edit().putString("log", trimmed).apply()
    }
}
