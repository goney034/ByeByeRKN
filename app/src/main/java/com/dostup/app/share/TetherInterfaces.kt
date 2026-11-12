package com.dostup.app.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The phone's own addresses on its hotspot, USB or Bluetooth tethering. Those interfaces carry
 * no internet of their own, while every network the phone uses (Wi-Fi as a client, mobile data,
 * the VPN) does; Android may list the tethering side as a network too, so the test is the
 * internet capability, not whether Android knows the interface. Address ranges differ between
 * phones, so none are assumed.
 */
object TetherInterfaces {
    /** Mobile modem and carrier service interfaces, which can lack internet and still face the operator. */
    private val CARRIER_PREFIXES = listOf("rmnet", "ccmni", "v4-", "epdg", "ifb", "dummy", "tun", "ipsec")

    fun addresses(context: Context): List<InetAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION") // allNetworks lists every network, which is what is needed here
        val online = connectivity.allNetworks
            .filter { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
            .mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }
            .toSet()
        return runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { iface ->
                runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) &&
                    iface.name !in online && CARRIER_PREFIXES.none { iface.name.startsWith(it) }
            }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
    }
}
