package com.dostup.app.autoapp

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.inputmethod.InputMethodManager
import com.dostup.app.AppLocale
import com.dostup.app.DostupApp
import com.dostup.app.R
import com.dostup.app.ui.MainActivity
import com.dostup.app.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fork: connect the VPN when one of the chosen apps comes to the front and disconnect when it is left.
 * The front app is read from the usage statistics, which needs the "usage access" switch of the phone.
 */
object AutoApps {
    private const val PREFS = "dostup"
    private const val KEY_ON = "autoAppsOn"
    private const val KEY_APPS = "autoApps"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ON, false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ON, on).apply()

    fun apps(context: Context): Set<String> = prefs(context).getStringSet(KEY_APPS, emptySet()).orEmpty().toSet()
    fun setApps(context: Context, apps: Set<String>) = prefs(context).edit().putStringSet(KEY_APPS, apps).apply()

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Starts or stops the watcher so that it matches the settings. Safe to call at any time. */
    fun sync(context: Context) {
        val intent = Intent(context, AppWatcherService::class.java)
        runCatching {
            if (enabled(context) && hasUsageAccess(context) && apps(context).isNotEmpty()) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}

class AppWatcherService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val context = AppLocale.wrap(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.autoapp_channel), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.autoapp_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (job?.isActive != true) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            job = s.launch { watch() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val app = DostupApp.instance
        val usage = getSystemService(UsageStatsManager::class.java)
        val ignored = buildSet {
            add(packageName)
            add("com.android.systemui")
            runCatching {
                getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) }
            }
        }
        var front: String? = null
        var since = System.currentTimeMillis() - 60_000
        var entered: String? = null
        var autoStarted = false
        var leftAt = 0L
        while (scope?.isActive == true) {
            val now = System.currentTimeMillis()
            runCatching {
                val events = usage.queryEvents(since, now)
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName !in ignored) front = event.packageName
                }
                since = now - 1_000
            }
            val chosen = AutoApps.apps(this)
            val wanted = front in chosen
            if (wanted) {
                leftAt = 0
                // Once per visit: a manual disconnect while the app is open is not undone.
                if (front != entered) {
                    entered = front
                    if (!app.store.wantConnected && app.vpn.status.value !is VpnStatus.Connected && VpnService.prepare(this) == null) {
                        app.vpn.connect()
                        autoStarted = true
                    }
                }
            } else {
                entered = null
                if (autoStarted) {
                    if (leftAt == 0L) leftAt = now
                    // A short wait, so a notification shade or a quick switch doesn't drop the tunnel.
                    if (now - leftAt >= LEAVE_GRACE_MS) {
                        app.vpn.disconnect()
                        autoStarted = false
                    }
                }
            }
            delay(1000)
        }
    }

    private companion object {
        const val CHANNEL = "autoapp"
        const val NOTIFICATION_ID = 2
        const val LEAVE_GRACE_MS = 4_000L
    }
}
