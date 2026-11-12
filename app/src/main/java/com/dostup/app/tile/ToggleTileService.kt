package com.dostup.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dostup.app.DostupApp
import com.dostup.app.R
import com.dostup.app.ui.MainActivity
import com.dostup.app.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick settings tile that toggles the VPN without opening the app. */
class ToggleTileService : TileService() {
    private var listening: Job? = null

    override fun onTileAdded() {
        DostupApp.instance.store.tileAdded = true
    }

    override fun onTileRemoved() {
        DostupApp.instance.store.tileAdded = false
    }

    override fun onStartListening() {
        DostupApp.instance.store.tileAdded = true
        val vpn = DostupApp.instance.vpn
        listening = CoroutineScope(Dispatchers.Main).launch { vpn.status.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val vpn = DostupApp.instance.vpn
        when (vpn.status.value) {
            VpnStatus.Disconnected, is VpnStatus.Failed ->
                if (VpnService.prepare(this) == null) vpn.connect() else openAppToConnect()
            else -> vpn.disconnect()
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            is VpnStatus.Connected, is VpnStatus.Working -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                is VpnStatus.Connected -> getString(R.string.notification_connected)
                is VpnStatus.Working -> getString(R.string.status_connecting)
                is VpnStatus.Failed -> getString(R.string.tile_error)
                VpnStatus.Disconnected -> getString(R.string.tile_disconnected)
            }
        }
        tile.updateTile()
    }

    companion object {
        /**
         * Asks the system to show its "Add tile?" dialog (Android 13+).
         * [onResult] gets true when the tile is in the panel afterwards.
         */
        fun requestAdd(context: Context, onResult: (Boolean) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return false
            statusBar.requestAddTileService(
                ComponentName(context, ToggleTileService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_tile),
                context.mainExecutor,
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                if (added) DostupApp.instance.store.tileAdded = true
                onResult(added)
            }
            return true
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppToConnect() {
        val intent = MainActivity.connectIntent(this)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
