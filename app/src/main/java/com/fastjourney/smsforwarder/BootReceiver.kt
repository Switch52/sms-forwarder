package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = Config(context)
        if (config.serviceEnabled && config.isConfigured()) {
            ForwarderService.start(context)
        }
    }
}
