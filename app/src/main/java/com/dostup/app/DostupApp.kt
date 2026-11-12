package com.dostup.app

import android.app.Application
import android.util.Log
import com.dostup.app.data.Store
import com.dostup.app.share.ShareProxy
import com.dostup.app.share.TetherInterfaces
import com.dostup.app.update.CatalogUpdater
import com.dostup.app.update.UpdateChecker
import com.dostup.app.vpn.VpnController
import com.dostup.app.vpn.VpnNotification
import com.dostup.app.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.GoBackend

class DostupApp : Application() {
    lateinit var store: Store
        private set
    lateinit var vpn: VpnController
        private set
    lateinit var updates: UpdateChecker
        private set
    lateinit var catalog: CatalogUpdater
        private set
    lateinit var share: ShareProxy
        private set

    /** Debug builds only: a screen orientation forced from adb, to check the wide layout. */
    val debugOrientation = MutableStateFlow<Int?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        updates = UpdateChecker(store, BuildConfig.RELEASES_REPO, BuildConfig.VERSION_NAME)
        // Before the VPN controller, so the first search already uses the newest saved list.
        catalog = CatalogUpdater(store, BuildConfig.RELEASES_REPO)
        VpnNotification.createChannel(this)
        GoBackend.setForegroundNotification(VpnNotification)
        vpn = VpnController(this, store)
        // Started by the system's "Always-on VPN" setting.
        GoBackend.setAlwaysOnCallback { vpn.connect() }
        share = ShareProxy(acceptOn = { address -> address in TetherInterfaces.addresses(this) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Sharing only while the tunnel is up: without it the proxy would send traffic around the VPN.
        scope.launch {
            combine(vpn.status, store.settings) { status, settings -> settings.shareVpn && status is VpnStatus.Connected }
                .distinctUntilChanged()
                .collect { on ->
                    runCatching { if (on) share.start() else share.stop() }
                        .onFailure { Log.w("Dostup/Share", "cannot ${if (on) "start" else "stop"} sharing", it) }
                }
        }
        scope.launch {
            // Only the kind of status changes the text, not every step of a search.
            combine(vpn.status, vpn.exit) { status, exit -> status::class to exit?.colo }
                .distinctUntilChanged()
                .collect { VpnNotification.update(this@DostupApp, vpn.status.value, vpn.exit.value, store.wantConnected) }
        }
    }

    companion object {
        lateinit var instance: DostupApp
            private set
    }
}
