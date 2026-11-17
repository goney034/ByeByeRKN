package com.dostup.app

import com.dostup.app.awg.Obfuscation
import com.dostup.app.probe.EndpointCatalog
import com.dostup.app.probe.EndpointScanner
import com.dostup.app.probe.HandshakeProbe
import com.dostup.app.warp.WarpApi
import kotlinx.coroutines.runBlocking
import org.amnezia.awg.crypto.Key
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Answers "which addresses and ports are WARP endpoints at all" with real handshakes from this
 * machine, to decide what goes into catalog/warp.json. Run it from an unfiltered network: it says
 * what exists, not what a given provider lets through.
 * Runs only with DOSTUP_LIVE=1 so regular builds stay offline.
 */
class LiveEndpointSurveyTest {
    private val random = Random(20261113)
    private val plain = Obfuscation.NONE.params()

    @Test
    fun surveyFamiliesAndPorts() = runBlocking {
        assumeTrue(System.getenv("DOSTUP_LIVE") == "1")
        val account = WarpApi().register()
        val scanner = EndpointScanner(HandshakeProbe(Key.fromBase64(account.privateKey).bytes, Key.fromBase64(account.peerPublicKey).bytes))

        suspend fun answered(endpoints: List<String>) = scanner.scanRoutes(endpoints.map { it to plain }).map { it.endpoint }.toSet()

        // 1. Is the whole /24 of each known family served, or only .1-.10 as the app assumes?
        println("survey: known families, $HOSTS_PER_FAMILY random hosts from .11-.254, ports $OFFICIAL_PORTS")
        for (prefix in EndpointCatalog.BUILT_IN.prefixes) {
            val hosts = (11..254).shuffled(random).take(HOSTS_PER_FAMILY)
            val endpoints = hosts.flatMap { host -> OFFICIAL_PORTS.take(2).map { "$prefix$host:$it" } }
            val ok = answered(endpoints)
            println("survey: known $prefix x: ${hosts.count { h -> OFFICIAL_PORTS.take(2).any { "$prefix$h:$it" in ok } }}/${hosts.size} hosts answer")
        }

        // 2. Ranges named in Cloudflare's firewall page or by other clients, not in the app yet.
        println("survey: candidate families")
        for (prefix in CANDIDATES) {
            val hosts = (1..254).shuffled(random).take(HOSTS_PER_FAMILY)
            val endpoints = hosts.flatMap { host -> OFFICIAL_PORTS.take(2).map { "$prefix$host:$it" } }
            val ok = answered(endpoints)
            println("survey: candidate $prefix x: ${hosts.count { h -> OFFICIAL_PORTS.take(2).any { "$prefix$h:$it" in ok } }}/${hosts.size} hosts answer")
        }

        // 3. Does every port of the app's list answer on a known-good address?
        val ports = EndpointCatalog.BUILT_IN.ports
        val okPorts = answered(ports.map { "162.159.192.1:$it" }).map { it.substringAfterLast(':').toInt() }.toSet()
        println("survey: ports on 162.159.192.1: ${okPorts.size}/${ports.size} answer; silent: ${ports - okPorts}")
        val extra = EXTRA_PORTS - ports.toSet()
        val okExtra = answered(extra.map { "162.159.192.1:$it" }).map { it.substringAfterLast(':').toInt() }.toSet()
        println("survey: extra ports tried ${extra.sorted()}: answer ${okExtra.sorted()}")
    }

    /** 162.159.193.x is Cloudflare's Zero Trust range: does it take consumer handshakes on any port? */
    @Test
    fun zeroTrustRange() = runBlocking {
        assumeTrue(System.getenv("DOSTUP_LIVE") == "1")
        val account = WarpApi().register()
        val scanner = EndpointScanner(HandshakeProbe(Key.fromBase64(account.privateKey).bytes, Key.fromBase64(account.peerPublicKey).bytes))
        // A fresh registration takes a moment to reach the endpoints: wait until a known one answers.
        val control = listOf("162.159.192.1:2408" to plain, "162.159.192.7:500" to plain)
        var before = 0
        for (attempt in 1..10) {
            before = scanner.scanRoutes(control).size
            if (before > 0) break
            kotlinx.coroutines.delay(1_000)
        }
        val endpoints = (1..10).flatMap { host -> OFFICIAL_PORTS.map { "162.159.193.$host:$it" } }
        val ok = scanner.scanRoutes(endpoints.map { it to plain }).map { it.endpoint }
        val after = scanner.scanRoutes(control).size
        println("survey: 162.159.193.1-10 on $OFFICIAL_PORTS: ${ok.size}/${endpoints.size} answer $ok")
        println("survey: control endpoints answered before: $before/2, after: $after/2")
    }

    private companion object {
        const val HOSTS_PER_FAMILY = 10
        val OFFICIAL_PORTS = listOf(2408, 500, 1701, 4500)
        val CANDIDATES = listOf(
            "162.159.194.", "162.159.196.", "162.159.197.", "162.159.198.", "162.159.199.", "162.159.204.",
            "188.114.100.", "188.114.101.", "8.6.113.", "8.34.71.",
        )
        val EXTRA_PORTS = setOf(443, 4443, 8443, 8095, 53, 123, 3478, 51820)
    }
}
