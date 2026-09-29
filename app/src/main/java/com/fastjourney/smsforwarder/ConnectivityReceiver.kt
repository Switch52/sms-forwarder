package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager

class ConnectivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return

        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            val config = Config(context)
            if (config.serviceEnabled && MessageQueue.size(context) > 0) {
                FileLog.log(context, "Connectivity restored, flushing queue")
                ForwarderService.flushQueue(context)
            }
        }
    }
}
