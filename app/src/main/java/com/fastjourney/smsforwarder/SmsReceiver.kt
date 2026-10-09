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

            val simDetect = detectSimSlot(context, intent)
            val simIndex = simDetect.slotIndex
            val recipient = config.getNumberForSim(simIndex)
            FileLog.log(context, ">> SIM slot: $simIndex, recipient: $recipient")
            reportSimConfigIssues(context, config, simDetect, recipient)

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
                        try {
                            AccountsApiClient.postMessageLog(
                                heartbeatUrl = config.heartbeatUrl,
                                apiKey = config.authPassword,
                                deviceId = config.deviceId,
                                simSlot = simIndex + 1,
                                simNumber = config.getNumberForSim(simIndex).ifBlank { null },
                                sender = sender,
                                body = message,
                                httpStatus = code,
                            )
                        } catch (e: Exception) {
                            FileLog.log(context, "!! SMS log error: ${e.javaClass.simpleName}: ${e.message}")
                        }
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

    private data class SimDetectResult(
        val slotIndex: Int,
        val androidNumber: String?,
        val displayName: String?,
        val detectionFailed: Boolean,
    )

    private fun detectSimSlot(context: Context, intent: Intent): SimDetectResult {
        val extras = intent.extras
            ?: return SimDetectResult(0, null, null, detectionFailed = true)

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
                    val androidNumber = subInfo.number?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    FileLog.log(
                        context,
                        ">> SIM detect: subInfo.slotIndex=${subInfo.simSlotIndex}, displayName=${subInfo.displayName}, number=${androidNumber ?: "(empty)"}",
                    )
                    return SimDetectResult(
                        slotIndex = subInfo.simSlotIndex.coerceIn(0, 1),
                        androidNumber = androidNumber,
                        displayName = subInfo.displayName?.toString(),
                        detectionFailed = false,
                    )
                } else {
                    FileLog.log(context, ">> SIM detect: no subInfo for subId=$subId, trying as slot index")
                    if (subId <= 1) {
                        return SimDetectResult(subId, null, null, detectionFailed = false)
                    }
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
                    val androidNumber = lookupSlotNumber(context, slot)
                    return SimDetectResult(slot, androidNumber, null, detectionFailed = false)
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
                            val androidNumber = sub.number?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                            FileLog.log(context, ">> SIM detect: matched subId=$subId to list index=$idx, slotIndex=${sub.simSlotIndex}")
                            return SimDetectResult(
                                slotIndex = sub.simSlotIndex.coerceIn(0, 1),
                                androidNumber = androidNumber,
                                displayName = sub.displayName?.toString(),
                                detectionFailed = false,
                            )
                        }
                    }
                    FileLog.log(context, ">> SIM detect: ${subs.size} active subs, none matched subId=$subId")
                }
            } catch (_: SecurityException) {}
        }

        FileLog.log(context, ">> SIM detect: FAILED — defaulting to slot 0")
        return SimDetectResult(0, lookupSlotNumber(context, 0), null, detectionFailed = true)
    }

    private fun lookupSlotNumber(context: Context, slotIndex: Int): String? {
        return try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            val subs = subManager?.activeSubscriptionInfoList ?: return null
            val match = subs.firstOrNull { it.simSlotIndex == slotIndex } ?: return null
            match.number?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun normalizePhone(value: String): String =
        value.filter { it.isDigit() }.trimStart('0')

    private fun phonesMatch(a: String, b: String): Boolean {
        val na = normalizePhone(a)
        val nb = normalizePhone(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || na.endsWith(nb) || nb.endsWith(na)
    }

    private fun reportSimConfigIssues(
        context: Context,
        config: Config,
        detect: SimDetectResult,
        configuredNumber: String,
    ) {
        val simSlot = detect.slotIndex + 1
        if (detect.detectionFailed) {
            FileLog.log(context, "!! SIM_DETECT_FAILED — reporting to accounts-api")
            postDeviceError(
                context = context,
                config = config,
                code = "SIM_DETECT_FAILED",
                message = "Could not detect which SIM slot received the SMS; defaulted to SIM $simSlot",
                simSlot = simSlot,
                configuredNumber = configuredNumber.ifBlank { null },
                detectedNumber = detect.androidNumber,
            )
        }

        if (configuredNumber.isBlank()) {
            FileLog.log(context, "!! SIM_CONFIG_MISSING for slot ${detect.slotIndex}")
            postDeviceError(
                context = context,
                config = config,
                code = "SIM_CONFIG_MISSING",
                message = "SMS arrived on SIM slot ${detect.slotIndex} but no number is configured for SIM $simSlot",
                simSlot = simSlot,
                configuredNumber = null,
                detectedNumber = detect.androidNumber,
            )
            return
        }

        val androidNumber = detect.androidNumber
        if (!androidNumber.isNullOrBlank() && !phonesMatch(configuredNumber, androidNumber)) {
            FileLog.log(
                context,
                "!! SIM_NUMBER_MISMATCH slot=${detect.slotIndex} configured=$configuredNumber android=$androidNumber",
            )
            postDeviceError(
                context = context,
                config = config,
                code = "SIM_NUMBER_MISMATCH",
                message = "Configured SIM $simSlot number ($configuredNumber) does not match Android number for that slot ($androidNumber)",
                simSlot = simSlot,
                configuredNumber = configuredNumber,
                detectedNumber = androidNumber,
            )
        }
    }

    private fun postDeviceError(
        context: Context,
        config: Config,
        code: String,
        message: String,
        simSlot: Int?,
        configuredNumber: String?,
        detectedNumber: String?,
    ) {
        try {
            AccountsApiClient.postError(
                heartbeatUrl = config.heartbeatUrl,
                apiKey = config.authPassword,
                deviceId = config.deviceId,
                code = code,
                message = message,
                simSlot = simSlot,
                configuredNumber = configuredNumber,
                detectedNumber = detectedNumber,
            )
        } catch (e: Exception) {
            FileLog.log(context, "!! Error report failed: ${e.javaClass.simpleName}: ${e.message}")
        }
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
