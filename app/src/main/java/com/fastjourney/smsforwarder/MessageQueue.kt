package com.fastjourney.smsforwarder

import android.content.Context
import org.json.JSONArray
import java.io.File

object MessageQueue {
    private const val FILE_NAME = "pending_queue.json"
    private const val MAX_QUEUED = 100

    fun enqueue(context: Context, payload: String) {
        synchronized(this) {
            val queue = load(context)
            if (queue.length() >= MAX_QUEUED) queue.remove(0)
            queue.put(payload)
            save(context, queue)
        }
    }

    fun drainAll(context: Context): List<String> {
        synchronized(this) {
            val queue = load(context)
            if (queue.length() == 0) return emptyList()
            val items = mutableListOf<String>()
            for (i in 0 until queue.length()) {
                items.add(queue.getString(i))
            }
            save(context, JSONArray())
            return items
        }
    }

    fun size(context: Context): Int {
        return load(context).length()
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
