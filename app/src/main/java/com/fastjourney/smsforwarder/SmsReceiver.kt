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
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val config = Config(context)
        if (!config.serviceEnabled || !config.isConfigured()) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsForwarder::SmsReceiveLock"
        ).apply { acquire(30_000) }

        val bundle = intent.extras ?: return
        @Suppress("UNCHECKED_CAST")
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format", "3gpp")

        val simIndex = detectSimSlot(context, intent)
        val recipient = config.getNumberForSim(simIndex)

        val messageBody = StringBuilder()
        var sender = ""

        for (pdu in pdus) {
            val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)
            messageBody.append(sms.messageBody)
            sender = sms.originatingAddress ?: ""
        }

        val message = messageBody.toString()

        if (config.otpFilterEnabled && !looksLikeOtp(message)) {
            FileLog.log(context, "SKIP SIM${simIndex + 1} from $sender — not OTP")
            return
        }

        val preview = message.take(40).replace("\n", " ")
        FileLog.log(context, "SMS SIM${simIndex + 1} ($recipient) from $sender: \"$preview\"")

        val payload = buildPayload(
            deviceId = config.deviceId,
            message = message,
            sender = sender,
            recipient = recipient,
            simNumber = simIndex + 1
        )

        ForwarderService.enqueueWebhook(context, config.webhookUrl, payload)

        if (wl.isHeld) wl.release()
    }

    private fun looksLikeOtp(message: String): Boolean {
        val lower = message.lowercase()
        val hasKeyword = listOf("otp", "code", "verification", "verify", "pin", "password", "تحقق")
            .any { lower.contains(it) }
        val hasDigitBlock = Regex("\\b\\d{4,8}\\b").containsMatchIn(message)
        return hasKeyword || hasDigitBlock
    }

    private fun detectSimSlot(context: Context, intent: Intent): Int {
        val extras = intent.extras ?: return 0

        val subId = when {
            extras.containsKey("android.telephony.extra.SUBSCRIPTION_INDEX") ->
                extras.getInt("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            extras.containsKey("subscription") ->
                extras.getInt("subscription", -1)
            else -> -1
        }

        if (subId >= 0) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                val subInfo = subManager?.getActiveSubscriptionInfo(subId)
                if (subInfo != null) return subInfo.simSlotIndex
            } catch (_: SecurityException) {}
        }

        val slotKeys = listOf(
            "android.telephony.extra.SLOT_INDEX",
            "slot", "simId", "simSlot", "phone",
            "com.android.phone.extra.slot"
        )
        for (key in slotKeys) {
            if (extras.containsKey(key)) {
                val slot = extras.getInt(key, -1)
                if (slot >= 0) return slot
            }
        }

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
