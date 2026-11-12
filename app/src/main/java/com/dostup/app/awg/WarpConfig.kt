package com.dostup.app.awg

import com.dostup.app.warp.WarpAccount
import org.amnezia.awg.crypto.KeyPair

/** Split-tunnel rule as it ends up in the config: at most one of the two lists is non-empty. */
data class SplitRule(val excluded: List<String> = emptyList(), val included: List<String> = emptyList()) {
    init {
        require(excluded.isEmpty() || included.isEmpty()) { "include and exclude lists are mutually exclusive" }
    }

    fun configLines(): List<String> = buildList {
        if (excluded.isNotEmpty()) add("ExcludedApplications = ${excluded.joinToString(", ")}")
        if (included.isNotEmpty()) add("IncludedApplications = ${included.joinToString(", ")}")
    }
}

/** Renders AmneziaWG configs in wg-quick format; the same text is used for the tunnel and for export. */
object WarpConfig {
    /** Tried in order when the handshake works but full-size packets get lost on the path. */
    val MTU_STEPS = listOf(1280, 1200, 1100)

    fun render(
        account: WarpAccount,
        endpoint: String,
        params: AwgParams,
        dns: List<String>,
        split: SplitRule,
        mtu: Int = MTU_STEPS.first(),
    ): String =
        buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${account.privateKey}")
            appendLine("Address = ${account.addressV4}/32, ${account.addressV6}/128")
            appendLine("DNS = ${dns.joinToString(", ")}")
            appendLine("MTU = $mtu")
            params.configLines().forEach(::appendLine)
            split.configLines().forEach(::appendLine)
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${account.peerPublicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $endpoint")
            appendLine("PersistentKeepalive = 25")
        }

    /**
     * The VPN interface for another engine (the MASQUE core) in the same format, so Android sets
     * it up exactly like the AmneziaWG one. The keys are throwaway placeholders the format needs.
     */
    fun renderInterface(addressV4: String, addressV6: String, dns: List<String>, split: SplitRule, mtu: Int): String =
        buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${KeyPair().privateKey.toBase64()}")
            appendLine("Address = $addressV4/32, $addressV6/128")
            appendLine("DNS = ${dns.joinToString(", ")}")
            appendLine("MTU = $mtu")
            split.configLines().forEach(::appendLine)
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${KeyPair().publicKey.toBase64()}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        }

    /** Replaces whatever app lists an imported config had with the app's own split settings. */
    fun applySplit(config: String, split: SplitRule): String {
        val lines = config.lines().filterNot {
            val key = it.substringBefore('=').trim().lowercase()
            key == "excludedapplications" || key == "includedapplications"
        }.toMutableList()
        val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (interfaceIndex >= 0) lines.addAll(interfaceIndex + 1, split.configLines())
        return lines.joinToString("\n")
    }

    fun endpointOf(config: String): String? = config.lines()
        .firstOrNull { it.substringBefore('=').trim().equals("Endpoint", ignoreCase = true) }
        ?.substringAfter('=')?.trim()
}
