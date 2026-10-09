package com.fastjourney.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort MSISDN discovery via carrier USSD when SubscriptionInfo.number is empty.
 * Dual-SIM: runs per subscription with createForSubscriptionId.
 */
object UssdNumberFetcher {

    data class SlotResult(
        val slotIndex: Int,
        val subscriptionId: Int,
        val ussdCode: String,
        val rawResponse: String,
        val parsedNumber: String?,
        val error: String?,
    )

    fun ussdCodesFor(carrierName: String, displayName: String, mcc: String, mnc: String): List<String> {
        val label = "$carrierName $displayName".lowercase()
        val codes = linkedSetOf<String>()

        when {
            label.contains("etisalat") || label.contains("e&") || label.contains("e and") ||
                (mcc == "602" && mnc == "03") || (mcc == "424" && mnc == "02") -> {
                // e& Egypt (602-03) / Etisalat Misr + UAE Etisalat (424-02)
                codes += listOf("*947#", "*878#", "*#100#")
            }
            label.contains("orange") || (mcc == "602" && mnc == "01") -> {
                codes += listOf("*#100#", "*100#", "#100#")
            }
            label.contains("vodafone") || (mcc == "602" && mnc == "02") -> {
                codes += listOf("*111*2#", "*#100#")
            }
            label.contains("we ") || label.startsWith("we") || (mcc == "602" && mnc == "04") -> {
                codes += listOf("*#100#", "*550#")
            }
            label.contains("du") || (mcc == "424" && mnc == "03") -> {
                codes += listOf("*#100#")
            }
        }

        // Generic fallbacks
        codes += listOf("*947#", "*#100#", "*878#")
        return codes.toList()
    }

    /**
     * Extract a plausible MSISDN from a USSD reply. Prefers +country / local mobile patterns.
     */
    fun parseNumber(response: String): String? {
        val text = response.replace('\u00A0', ' ').trim()
        if (text.isBlank()) return null

        // Explicit +CC…
        Regex("""\+\d{10,15}""").find(text)?.value?.let { return it }

        // Egypt mobile 01X XXXXXXXX
        Regex("""(?<!\d)(01[0125]\d{8})(?!\d)""").find(text)?.groupValues?.get(1)?.let {
            return "+20${it.removePrefix("0")}"
        }

        // UAE mobile 05X XXXXXXX
        Regex("""(?<!\d)(05\d{8})(?!\d)""").find(text)?.groupValues?.get(1)?.let {
            return "+971${it.removePrefix("0")}"
        }

        // Bare 20… / 971… country codes without +
        Regex("""(?<!\d)((?:20|971)\d{8,12})(?!\d)""").find(text)?.groupValues?.get(1)?.let {
            return "+$it"
        }

        // Last resort: longest 9–15 digit run that looks like a phone (not an amount like 5.00)
        val candidates = Regex("""(?<!\d)\d{9,15}(?!\d)""").findAll(text).map { it.value }.toList()
        val best = candidates.maxByOrNull { it.length } ?: return null
        return if (best.startsWith("20") || best.startsWith("971")) "+$best" else best
    }

    fun fetchForActiveSims(
        context: Context,
        onProgress: (String) -> Unit,
        onFinished: (List<SlotResult>) -> Unit,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onFinished(emptyList())
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onFinished(
                listOf(
                    SlotResult(
                        slotIndex = -1,
                        subscriptionId = -1,
                        ussdCode = "",
                        rawResponse = "",
                        parsedNumber = null,
                        error = "CALL_PHONE permission required for USSD",
                    )
                )
            )
            return
        }

        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as? SubscriptionManager
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (subManager == null || telephony == null) {
            onFinished(emptyList())
            return
        }

        val subs = try {
            subManager.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (subs.isEmpty()) {
            onFinished(emptyList())
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val results = mutableListOf<SlotResult>()
        var index = 0

        fun next() {
            if (index >= subs.size) {
                onFinished(results.toList())
                return
            }
            val info = subs[index++]
            val codes = ussdCodesFor(
                carrierName = info.carrierName?.toString().orEmpty(),
                displayName = info.displayName?.toString().orEmpty(),
                mcc = info.mccString ?: info.mcc.toString(),
                mnc = info.mncString ?: info.mnc.toString().padStart(2, '0'),
            )
            onProgress("Slot ${info.simSlotIndex + 1}: trying USSD…")
            tryCodes(telephony, info.subscriptionId, info.simSlotIndex, codes, handler, onProgress) { result ->
                results.add(result)
                // Small gap between SIMs so modem settles
                handler.postDelayed({ next() }, 800)
            }
        }

        next()
    }

    private fun tryCodes(
        telephony: TelephonyManager,
        subscriptionId: Int,
        slotIndex: Int,
        codes: List<String>,
        handler: Handler,
        onProgress: (String) -> Unit,
        done: (SlotResult) -> Unit,
    ) {
        var codeIndex = 0

        fun tryNext() {
            if (codeIndex >= codes.size) {
                done(
                    SlotResult(
                        slotIndex = slotIndex,
                        subscriptionId = subscriptionId,
                        ussdCode = codes.lastOrNull().orEmpty(),
                        rawResponse = "",
                        parsedNumber = null,
                        error = "No usable number in USSD replies",
                    )
                )
                return
            }
            val code = codes[codeIndex++]
            onProgress("Slot ${slotIndex + 1}: $code")
            sendOne(telephony, subscriptionId, slotIndex, code, handler) { result ->
                if (result.parsedNumber != null) {
                    done(result)
                } else {
                    tryNext()
                }
            }
        }

        tryNext()
    }

    private fun sendOne(
        telephony: TelephonyManager,
        subscriptionId: Int,
        slotIndex: Int,
        code: String,
        handler: Handler,
        done: (SlotResult) -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        fun finish(result: SlotResult) {
            if (finished.compareAndSet(false, true)) {
                done(result)
            }
        }

        val timeout = Runnable {
            finish(
                SlotResult(
                    slotIndex = slotIndex,
                    subscriptionId = subscriptionId,
                    ussdCode = code,
                    rawResponse = "",
                    parsedNumber = null,
                    error = "USSD timeout",
                )
            )
        }
        handler.postDelayed(timeout, 20_000)

        try {
            val tm = telephony.createForSubscriptionId(subscriptionId)
            tm.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence,
                    ) {
                        handler.removeCallbacks(timeout)
                        val raw = response.toString()
                        finish(
                            SlotResult(
                                slotIndex = slotIndex,
                                subscriptionId = subscriptionId,
                                ussdCode = request,
                                rawResponse = raw,
                                parsedNumber = parseNumber(raw),
                                error = null,
                            )
                        )
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int,
                    ) {
                        handler.removeCallbacks(timeout)
                        finish(
                            SlotResult(
                                slotIndex = slotIndex,
                                subscriptionId = subscriptionId,
                                ussdCode = request,
                                rawResponse = "",
                                parsedNumber = null,
                                error = "USSD failed ($failureCode)",
                            )
                        )
                    }
                },
                handler,
            )
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeout)
            finish(
                SlotResult(
                    slotIndex = slotIndex,
                    subscriptionId = subscriptionId,
                    ussdCode = code,
                    rawResponse = "",
                    parsedNumber = null,
                    error = "SecurityException: ${e.message}",
                )
            )
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            finish(
                SlotResult(
                    slotIndex = slotIndex,
                    subscriptionId = subscriptionId,
                    ussdCode = code,
                    rawResponse = "",
                    parsedNumber = null,
                    error = e.javaClass.simpleName + ": " + (e.message ?: ""),
                )
            )
        }
    }
}
