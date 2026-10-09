package com.fastjourney.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class DetectedSim(
    val slotIndex: Int,
    /** MSISDN from the OS when available; blank if carrier did not provision it. */
    val number: String,
    val carrierName: String,
    val displayName: String,
)

object SimInfo {

    /**
     * Best-effort read of active SIM slot numbers via SubscriptionInfo / TelephonyManager.
     * Many prepaid / dual-SIM carriers leave [DetectedSim.number] empty.
     */
    fun detect(context: Context): List<DetectedSim> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as? SubscriptionManager ?: return emptyList()

        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        return try {
            val subs = subManager.activeSubscriptionInfoList ?: return emptyList()
            subs.map { info ->
                var number = info.number?.trim().orEmpty()
                if (number.isBlank() && telephony != null) {
                    try {
                        val tm = telephony.createForSubscriptionId(info.subscriptionId)
                        number = tm.line1Number?.trim().orEmpty()
                    } catch (_: Exception) {
                        // ignore — common when MSISDN is not provisioned
                    }
                }
                DetectedSim(
                    slotIndex = info.simSlotIndex,
                    number = number,
                    carrierName = info.carrierName?.toString().orEmpty(),
                    displayName = info.displayName?.toString().orEmpty(),
                )
            }.sortedBy { it.slotIndex }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Digits-only compare so +20… / 20… / leading 0 variants can match. */
    fun numbersMatch(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        return da == db || da.endsWith(db) || db.endsWith(da)
    }

    fun numberForSlot(detected: List<DetectedSim>, slotIndex: Int): String {
        return detected.firstOrNull { it.slotIndex == slotIndex }?.number.orEmpty()
    }
}
