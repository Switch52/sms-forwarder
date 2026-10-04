package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        FileLog.log(context, ">> SMS broadcast received (action=${intent.action})")

        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            FileLog.log(context, "!! Ignored: wrong action ${intent.action}")
            return
        }

        val config = Config(context)
        if (!config.serviceEnabled) {
            FileLog.log(context, "!! Ignored: service disabled")
            return
        }
        if (!config.isConfigured()) {
            FileLog.log(context, "!! Ignored: not configured (webhook=${config.webhookUrl.isNotBlank()}, sim1=${config.sim1Number.isNotBlank()}, sim2=${config.sim2Number.isNotBlank()})")
            return
        }

        val pendingResult = goAsync()

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsForwarder::SmsReceiveLock"
        ).apply { acquire(60_000) }

        try {
            val bundle = intent.extras
            if (bundle == null) {
                FileLog.log(context, "!! ERROR: intent has no extras")
                return
            }

            val keys = bundle.keySet().joinToString(", ")
            FileLog.log(context, ">> Intent extras: $keys")

            @Suppress("UNCHECKED_CAST")
            val pdus = bundle.get("pdus") as? Array<*>
            if (pdus == null) {
                FileLog.log(context, "!! ERROR: no PDUs in intent")
                return
            }
            val format = bundle.getString("format", "3gpp")
            FileLog.log(context, ">> PDUs: ${pdus.size}, format=$format")

            val simIndex = detectSimSlot(context, intent)
            val recipient = config.getNumberForSim(simIndex)
            FileLog.log(context, ">> SIM slot: $simIndex, recipient: $recipient")

            val messageBody = StringBuilder()
            var sender = ""

            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)
                messageBody.append(sms.messageBody)
                sender = sms.originatingAddress ?: ""
            }

            val message = messageBody.toString()
            val preview = message.take(60).replace("\n", " ")
            FileLog.log(context, ">> SMS from $sender: \"$preview\"")

            val payload = buildPayload(
                deviceId = config.deviceId,
                message = message,
                sender = sender,
                recipient = recipient,
                simNumber = simIndex + 1
            )

            val webhookUrl = config.webhookUrl
            FileLog.log(context, ">> Posting directly to $webhookUrl (bypassing service)")

            Thread {
                try {
                    val code = doPost(webhookUrl, payload, config)

                    if (code in 200..299) {
                        FileLog.log(context, "-> SENT SIM${simIndex + 1} from $sender (HTTP $code)")
                    } else {
                        MessageQueue.enqueue(context, payload)
                        FileLog.log(context, "-> QUEUED SIM${simIndex + 1} from $sender (HTTP $code)")
                    }
                } catch (e: Exception) {
                    MessageQueue.enqueue(context, payload)
                    FileLog.log(context, "-> QUEUED SIM${simIndex + 1} from $sender (${e.javaClass.simpleName}: ${e.message})")
                } finally {
                    if (wl.isHeld) wl.release()
                    pendingResult.finish()
                }

                try { ForwarderService.start(context) } catch (_: Exception) {}
            }.start()

        } catch (e: Exception) {
            FileLog.log(context, "!! CRASH in SmsReceiver: ${e.javaClass.simpleName}: ${e.message}")
            if (wl.isHeld) wl.release()
            pendingResult.finish()
        }
    }

    private fun doPost(webhookUrl: String, payload: String, config: Config): Int {
        val url = URL(webhookUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true

        if (config.hasAuth()) {
            val creds = "${config.authUsername}:${config.authPassword}"
            val encoded = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload) }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    private fun detectSimSlot(context: Context, intent: Intent): Int {
        val extras = intent.extras ?: return 0

        val intDump = StringBuilder()
        for (key in extras.keySet()) {
            val v = try { extras.getInt(key, -999) } catch (_: Exception) { -999 }
            if (v != -999) intDump.append("$key=$v ")
        }
        FileLog.log(context, ">> SIM extras (ints): $intDump")

        val subKeys = listOf(
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscription",
            "sub_id",
            "simnum",
            "com.android.phone.extra.subscription"
        )
        var subId = -1
        for (key in subKeys) {
            if (extras.containsKey(key)) {
                subId = extras.getInt(key, -1)
                if (subId >= 0) {
                    FileLog.log(context, ">> SIM detect: subId=$subId from key=$key")
                    break
                }
            }
        }

        if (subId >= 0) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                val subInfo = subManager?.getActiveSubscriptionInfo(subId)
                if (subInfo != null) {
                    FileLog.log(context, ">> SIM detect: subInfo.slotIndex=${subInfo.simSlotIndex}, displayName=${subInfo.displayName}")
                    return subInfo.simSlotIndex
                } else {
                    FileLog.log(context, ">> SIM detect: no subInfo for subId=$subId, trying as slot index")
                    if (subId <= 1) return subId
                }
            } catch (e: SecurityException) {
                FileLog.log(context, "!! SIM detect: SecurityException — READ_PHONE_STATE missing")
            }
        }

        val slotKeys = listOf(
            "android.telephony.extra.SLOT_INDEX",
            "slot",
            "slotId",
            "slotIdx",
            "simId",
            "simSlot",
            "simNum",
            "phone",
            "com.android.phone.extra.slot",
            "simslot",
            "sim_slot",
            "slot_id",
            "extra_slot_id",
            "slot_index",
            "sim_id",
            "simPosition",
            "sim_position"
        )
        for (key in slotKeys) {
            if (extras.containsKey(key)) {
                val slot = extras.getInt(key, -1)
                if (slot >= 0 && slot <= 1) {
                    FileLog.log(context, ">> SIM detect: slot=$slot from key=$key")
                    return slot
                }
            }
        }

        if (subId >= 0) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                val subs = subManager?.activeSubscriptionInfoList
                if (subs != null) {
                    for ((idx, sub) in subs.withIndex()) {
                        if (sub.subscriptionId == subId) {
                            FileLog.log(context, ">> SIM detect: matched subId=$subId to list index=$idx, slotIndex=${sub.simSlotIndex}")
                            return sub.simSlotIndex
                        }
                    }
                    FileLog.log(context, ">> SIM detect: ${subs.size} active subs, none matched subId=$subId")
                }
            } catch (_: SecurityException) {}
        }

        FileLog.log(context, ">> SIM detect: FAILED — defaulting to slot 0")
        return 0
    }

    private fun buildPayload(
        deviceId: String,
        message: String,
        sender: String,
        recipient: String,
        simNumber: Int
    ): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        isoFormat.timeZone = TimeZone.getDefault()

        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("event", "sms:received")
            put("id", UUID.randomUUID().toString())
            put("payload", JSONObject().apply {
                put("message", message)
                put("sender", sender)
                put("recipient", recipient)
                put("simNumber", simNumber)
                put("receivedAt", isoFormat.format(Date()))
            })
        }
        return json.toString()
    }
}
