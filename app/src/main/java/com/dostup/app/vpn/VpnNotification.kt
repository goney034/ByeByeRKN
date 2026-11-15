package com.dostup.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dostup.app.AppLocale
import com.dostup.app.R
import com.dostup.app.ui.MainActivity
import org.amnezia.awg.backend.GoBackend

/** The ongoing notification of the foreground VpnService, kept in step with the VPN status. */
object VpnNotification : GoBackend.ForegroundNotification {
    private const val CHANNEL = "vpn"

    override fun id() = 1

    override fun build(base: Context): Notification {
        val context = AppLocale.wrap(base)
        return notification(context, context.getString(R.string.status_connecting))
    }

    fun createChannel(base: Context) {
        val context = AppLocale.wrap(base)
        val channel = NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun update(base: Context, status: VpnStatus, exit: ExitInfo?, wantConnected: Boolean, traffic: Traffic? = null) {
        val context = AppLocale.wrap(base)
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = when (status) {
            is VpnStatus.Connected -> listOfNotNull(
                context.getString(R.string.notification_connected),
                exit?.city(context.resources.configuration.locales[0].language),
            ).joinToString(" · ")
            is VpnStatus.Working -> context.getString(R.string.status_connecting)
            is VpnStatus.Failed -> if (wantConnected) context.getString(R.string.notification_retrying) else null
            VpnStatus.Disconnected -> null
        }
        // Fork: second line with the downloaded and uploaded amounts while connected.
        val usage = if (status is VpnStatus.Connected && traffic != null) {
            context.getString(R.string.notification_traffic, formatBytes(context, traffic.rx), formatBytes(context, traffic.tx))
        } else null
        if (text == null) manager.cancel(id()) else manager.notify(id(), notification(context, text, usage))
    }

    private fun formatBytes(context: Context, bytes: Long): String = when {
        bytes < 1024 -> context.getString(R.string.size_bytes, bytes)
        bytes < 1024 * 1024 -> context.getString(R.string.size_kb, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> context.getString(R.string.size_mb, bytes / (1024.0 * 1024))
        else -> context.getString(R.string.size_gb, bytes / (1024.0 * 1024 * 1024))
    }

    private fun notification(context: Context, text: String, usage: String? = null): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(if (usage == null) text else "$text · $usage")
            .apply { if (usage != null) setStyle(Notification.BigTextStyle().bigText("$text\n$usage")) }
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
