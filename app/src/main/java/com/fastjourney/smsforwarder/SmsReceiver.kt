package com.fastjourney.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import org.json.JSONObject
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

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsForwarder::SmsReceiveLock"
        ).apply { acquire(30_000) }

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

            FileLog.log(context, ">> Payload built, forwarding to ${config.webhookUrl}")
            ForwarderService.enqueueWebhook(context, config.webhookUrl, payload)
        } catch (e: Exception) {
            FileLog.log(context, "!! CRASH in SmsReceiver: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun detectSimSlot(context: Context, intent: Intent): Int {
        val extras = intent.extras ?: return 0

        // Dump all int extras for debugging SIM detection on unknown OEMs
        val intDump = StringBuilder()
        for (key in extras.keySet()) {
            val v = try { extras.getInt(key, -999) } catch (_: Exception) { -999 }
            if (v != -999) intDump.append("$key=$v ")
        }
        FileLog.log(context, ">> SIM extras (ints): $intDump")

        // Try subscription ID from standard and OEM keys
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

        // Map subscription ID to slot index via SubscriptionManager
        if (subId >= 0) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                val subInfo = subManager?.getActiveSubscriptionInfo(subId)
                if (subInfo != null) {
                    FileLog.log(context, ">> SIM detect: subInfo.slotIndex=${subInfo.simSlotIndex}, displayName=${subInfo.displayName}")
                    return subInfo.simSlotIndex
                } else {
                    // subId might BE the slot index on some OEMs
                    FileLog.log(context, ">> SIM detect: no subInfo for subId=$subId, trying as slot index")
                    if (subId <= 1) return subId
                }
            } catch (e: SecurityException) {
                FileLog.log(context, "!! SIM detect: SecurityException — READ_PHONE_STATE missing")
                // Fall through to slot key detection
            }
        }

        // Try direct slot index keys (including OEM-specific ones)
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
            // Oppo / ColorOS / Realme
            "simslot",
            "sim_slot",
            "slot_id",
            // Xiaomi / MIUI
            "extra_slot_id",
            "slot_index",
            // Samsung
            "sim_id",
            // Huawei
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

        // Last resort: try to match subscription ID to active subscriptions list
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
