package com.dostup.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dostup.app.R
import com.dostup.app.ui.MainActivity
import org.amnezia.awg.backend.GoBackend

/** The ongoing notification of the foreground VpnService, kept in step with the VPN status. */
object VpnNotification : GoBackend.ForegroundNotification {
    private const val CHANNEL = "vpn"

    override fun id() = 1

    override fun build(context: Context): Notification = notification(context, context.getString(R.string.status_connecting))

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun update(context: Context, status: VpnStatus, exit: ExitInfo?, wantConnected: Boolean) {
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
        if (text == null) manager.cancel(id()) else manager.notify(id(), notification(context, text))
    }

    private fun notification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
