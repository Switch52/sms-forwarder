package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = Config(context)
        if (!config.serviceEnabled || !config.isConfigured()) return

        FileLog.log(context, ">> BootReceiver: ${intent.action}")
        // Re-arm exact one-shot watchdog on every fire (including WATCHDOG itself).
        ForwarderService.scheduleWatchdog(context)
        ForwarderService.start(context)
    }
}
