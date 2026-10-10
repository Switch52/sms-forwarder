package com.fastjourney.smsforwarder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MessageQueue {
    private const val FILE_NAME = "pending_queue.json"
    private const val MAX_QUEUED = 100
    const val FLUSH_MAX_AGE_MS = 60_000L

    fun enqueue(context: Context, payload: String) {
        synchronized(this) {
            val queue = load(context)
            if (queue.length() >= MAX_QUEUED) queue.remove(0)
            queue.put(
                JSONObject()
                    .put("payload", payload)
                    .put("queuedAt", System.currentTimeMillis()),
            )
            save(context, queue)
        }
    }

    fun drainAll(context: Context): List<String> {
        synchronized(this) {
            val queue = load(context)
            if (queue.length() == 0) return emptyList()
            val items = mutableListOf<String>()
            for (i in 0 until queue.length()) {
                items.add(readPayload(queue.get(i)))
            }
            save(context, JSONArray())
            return items
        }
    }

    /** Only messages queued within [maxAgeMs]; older entries are dropped. */
    fun drainRecent(context: Context, maxAgeMs: Long = FLUSH_MAX_AGE_MS): List<String> {
        synchronized(this) {
            val queue = load(context)
            if (queue.length() == 0) return emptyList()
            val now = System.currentTimeMillis()
            val recent = mutableListOf<String>()
            for (i in 0 until queue.length()) {
                val entry = queue.get(i)
                val payload = readPayload(entry)
                val queuedAt = readQueuedAt(entry, now)
                if (now - queuedAt <= maxAgeMs) {
                    recent.add(payload)
                }
            }
            save(context, JSONArray())
            return recent
        }
    }

    fun size(context: Context): Int {
        return load(context).length()
    }

    private fun readPayload(entry: Any): String {
        return when (entry) {
            is JSONObject -> entry.optString("payload", "")
            else -> entry.toString()
        }
    }

    private fun readQueuedAt(entry: Any, fallbackNow: Long): Long {
        return when (entry) {
            is JSONObject -> entry.optLong("queuedAt", fallbackNow)
            else -> fallbackNow
        }
    }

    private fun load(context: Context): JSONArray {
        val file = queueFile(context)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun save(context: Context, queue: JSONArray) {
        queueFile(context).writeText(queue.toString())
    }

    private fun queueFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
