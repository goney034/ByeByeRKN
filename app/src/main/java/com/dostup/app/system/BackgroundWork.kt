package com.dostup.app.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.dostup.app.R

/**
 * Keeps the system from stopping the VPN in the background. Stock Android only has battery
 * optimisation; Xiaomi, Huawei, OPPO, vivo and others add autostart managers of their own that
 * stop apps regardless, behind settings screens at maker-specific addresses. Only screens that
 * exist on this phone are offered.
 */
object BackgroundWork {
    /** Makers known to stop background apps on their own, as collected by dontkillmyapp.com. */
    private val AGGRESSIVE_MAKERS = setOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo",
        "meizu", "asus", "tecno", "infinix", "itel", "lenovo", "letv", "nubia", "zte",
    )

    /** Autostart managers, newest first; package names must also be listed in the manifest's queries. */
    private val AUTOSTART = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    )

    val aggressiveMaker: Boolean
        get() = Build.MANUFACTURER.lowercase() in AGGRESSIVE_MAKERS || Build.BRAND.lowercase() in AGGRESSIVE_MAKERS

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Asks in one system dialog; phones without it get the full list instead. */
    @SuppressLint("BatteryLife") // A VPN must keep running in the background; Play allows this for VPN apps.
    fun requestBatteryUnrestricted(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
            openBatterySettings(context)

    fun openBatterySettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** The maker's autostart screen, or null when this phone has none. */
    fun autostartIntent(context: Context): Intent? = AUTOSTART.asSequence()
        .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
        .firstOrNull { opens(context, it) }

    /** Xiaomi's own per-app battery saver, which works apart from Android's. */
    fun makerBatteryIntent(context: Context): Intent? =
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.getString(R.string.app_name))
            .takeIf { opens(context, it) }

    fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** Present and open to other apps: a hidden activity would only throw when started. */
    private fun opens(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.exported == true
}
