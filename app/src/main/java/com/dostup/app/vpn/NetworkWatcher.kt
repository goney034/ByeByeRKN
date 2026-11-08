package com.dostup.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Tracks the network in use. The first one seen at start-up is not a change, but one that appears
 * after a complete loss is: the tunnel needs a new handshake, and a pending retry should run now.
 */
internal class PrimaryNetwork<T : Any> {
    @Volatile
    var current: T? = null
        private set
    private var seenBefore = false

    /** Moves to [next] and returns whether that is a change to react to. */
    fun moveTo(next: T?): Boolean {
        current = next
        if (next == null) return false
        val changed = seenBefore
        seenBefore = true
        return changed
    }
}

/**
 * Reports when the physical network under the VPN changes (Wi-Fi to mobile and back, another
 * Wi-Fi). The app's own default network is the VPN itself, so this watches non-VPN networks
 * and treats the best one (Wi-Fi, then Ethernet, then cellular) as the one in use.
 */
class NetworkWatcher(private val context: Context, private val onChange: (label: String) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = HashMap<Network, NetworkCapabilities>()
    private val primary = PrimaryNetwork<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(networks) { networks[network] = capabilities }
            recompute()
        }

        override fun onLost(network: Network) {
            synchronized(networks) { networks.remove(network) }
            recompute()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "cannot watch networks", it) }
    }

    val isCellular: Boolean
        get() = synchronized(networks) { networks[primary.current]?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }

    /** Like [currentKey], but gives the callback a moment to report networks right after start-up. */
    suspend fun awaitKey(): String {
        repeat(AWAIT_KEY_TRIES) {
            if (primary.current != null) return currentKey()
            delay(AWAIT_KEY_STEP_MS)
        }
        return currentKey()
    }

    /**
     * Identifies the current network, so routes can be remembered per network: the operator on
     * mobile, the gateway and DNS on Wi-Fi (the SSID would need the location permission).
     */
    fun currentKey(): String {
        val (network, capabilities) = synchronized(networks) { primary.current to networks[primary.current] }
        if (network == null || capabilities == null) return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val telephony = context.getSystemService(TelephonyManager::class.java)
                "cell:" + (telephony?.networkOperator?.takeIf { it.isNotEmpty() } ?: "unknown")
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val link = connectivity.getLinkProperties(network)
                val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
                "wifi:$gateway/${link?.dnsServers?.firstOrNull()?.hostAddress}"
            }
            else -> "other"
        }
    }

    private fun recompute() {
        val (next, label) = synchronized(networks) {
            networks.entries.minByOrNull { rank(it.value) }?.let { it.key to label(it.value) } ?: (null to "بدون شبکه")
        }
        if (next == primary.current) return
        val changed = primary.moveTo(next)
        Log.i(TAG, "underlying network: $label")
        if (changed) onChange(label)
    }

    private fun rank(c: NetworkCapabilities) = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }

    private fun label(c: NetworkCapabilities) = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "شبکه موبایل"
        else -> "شبکه دیگر"
    }

    private companion object {
        const val TAG = "Dostup/Net"
        const val AWAIT_KEY_TRIES = 30
        const val AWAIT_KEY_STEP_MS = 50L
    }
}
