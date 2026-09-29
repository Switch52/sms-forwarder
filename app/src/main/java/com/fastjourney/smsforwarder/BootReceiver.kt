package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = Config(context)
        if (!config.serviceEnabled || !config.isConfigured()) return

        when (intent.action) {
            "com.fastjourney.smsforwarder.WATCHDOG" -> {
                ForwarderService.start(context)
                if (config.heartbeatEnabled) {
                    ForwarderService.heartbeat(context)
                }
            }
            else -> ForwarderService.start(context)
        }
    }
}
