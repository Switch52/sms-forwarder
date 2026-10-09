package com.fastjourney.smsforwarder

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Opens standard + OEM screens so the foreground service survives background kills.
 * Many OEM toggles cannot be flipped by the app — we deep-link and nag until set.
 */
object KeepAliveHelper {

    private const val PREFS = "sms_forwarder"
    private const val KEY_OEM_ACKED = "keepalive_oem_acked"
    private const val KEY_RECENTS_ACKED = "keepalive_recents_acked"
    private const val KEY_WIZARD_DONE = "keepalive_wizard_done"

    fun isBatteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun isAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return listOf("huawei", "honor", "xiaomi", "redmi", "poco", "oppo", "realme",
            "vivo", "iqoo", "oneplus", "infinix", "tecno", "itel", "transsion")
            .any { m.contains(it) || b.contains(it) }
    }

    fun isOemAcked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OEM_ACKED, false)

    fun isRecentsAcked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECENTS_ACKED, false)

    fun setOemAcked(context: Context, value: Boolean = true) {
        prefs(context).edit().putBoolean(KEY_OEM_ACKED, value).apply()
    }

    fun setRecentsAcked(context: Context, value: Boolean = true) {
        prefs(context).edit().putBoolean(KEY_RECENTS_ACKED, value).apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        if (isBatteryOptimized(context)) return false
        if (!canScheduleExactAlarms(context)) return false
        if (isAggressiveOem() && !isOemAcked(context)) return false
        if (isAggressiveOem() && !isRecentsAcked(context)) return false
        return true
    }

    fun checklistText(context: Context): String {
        val lines = mutableListOf<String>()
        lines += if (isBatteryOptimized(context)) {
            "✗ Battery: still Optimized — must be Unrestricted"
        } else {
            "✓ Battery: Unrestricted"
        }
        lines += if (canScheduleExactAlarms(context)) {
            "✓ Exact alarms: allowed"
        } else {
            "✗ Exact alarms: blocked — needed for watchdog restart"
        }
        if (isAggressiveOem()) {
            lines += if (isOemAcked(context)) {
                "✓ Auto-start / background: confirmed"
            } else {
                "✗ Auto-start / background: open OEM settings and allow"
            }
            lines += if (isRecentsAcked(context)) {
                "✓ Recents: app locked"
            } else {
                "✗ Recents: open Recents → lock SMS Forwarder (padlock)"
            }
            lines += "Phone: ${Build.MANUFACTURER} ${Build.MODEL}"
        }
        return lines.joinToString("\n")
    }

    /** Open the next incomplete keep-alive screen (one at a time). */
    fun runWizard(context: Context) {
        when {
            isBatteryOptimized(context) -> requestBatteryExemption(context)
            !canScheduleExactAlarms(context) -> requestExactAlarmPermission(context)
            isAggressiveOem() && !isOemAcked(context) -> openOemAutostartSettings(context)
            else -> { /* checklist only needs manual Recents ack */ }
        }
        prefs(context).edit().putBoolean(KEY_WIZARD_DONE, true).apply()
    }

    fun requestBatteryExemption(context: Context) {
        if (!isBatteryOptimized(context)) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openAppBatterySettings(context)
        }
    }

    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (canScheduleExactAlarms(context)) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    fun openAppBatterySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    /** Best-effort OEM autostart / protected-apps screens. */
    fun openOemAutostartSettings(context: Context): Boolean {
        val candidates = oemIntents(context)
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        openAppDetails(context)
        return false
    }

    private fun oemIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        val list = mutableListOf<Intent>()

        // Huawei / Honor
        list += component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        list += component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        list += component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        list += component("com.hihonor.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        list += component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        list += component("com.hihonor.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")

        // Xiaomi / MIUI
        list += Intent("miui.intent.action.OP_AUTO_START").putExtra("packageName", pkg)
        list += component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

        // Oppo / Realme / ColorOS
        list += component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        list += component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        list += component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")

        // Vivo
        list += component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        list += component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")

        // OnePlus
        list += component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")

        // Transsion (Infinix / Tecno / Itel)
        list += component("com.transsion.phonemanager", "com.transsion.phonemanager.module.appmanager.AppManagerActivity")
        list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
        }

        return list
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
