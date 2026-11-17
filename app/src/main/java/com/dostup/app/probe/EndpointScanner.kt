package com.dostup.app.probe

import com.dostup.app.awg.AwgParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object WarpEndpoints {
    const val HOST = "engage.cloudflareclient.com"

    // The lists live in EndpointCatalog, so a newer signed copy can replace them at run time.
    val ports: List<Int> get() = EndpointCatalog.current.ports
    val prefixes: List<String> get() = EndpointCatalog.current.prefixes

    /**
     * IPv6 endpoints embed the IPv4 one in their last 32 bits: engage.cloudflareclient.com is
     * 2606:4700:d0::a29f:c001, i.e. 162.159.192.1. IPv6 is often filtered less than IPv4.
     */
    val v6Prefixes: List<String> get() = EndpointCatalog.current.v6Prefixes

    private val wellKnown: List<String> get() = EndpointCatalog.current.wellKnown

    /**
     * Covers every address family, because the data centre a route exits from depends on the
     * family. About one in five is IPv6, free to probe: unreachable ones fail instantly.
     */
    fun sample(count: Int, random: Random = Random.Default): List<String> {
        val catalog = EndpointCatalog.current
        // Names would need DNS, which a scan must not wait for; the catalog may still carry one.
        val result = LinkedHashSet(wellKnown.filter { isLiteral(hostOf(it)) })
        val families = prefixes.shuffled(random)
        var next = 0
        // A small downloaded catalog may hold fewer distinct endpoints than asked for.
        var draws = 0
        while (result.size < count && draws++ < count * MAX_DRAWS_PER_ENDPOINT) {
            val port = ports.random(random)
            result += if (catalog.v6Prefixes.isNotEmpty() && random.nextInt(5) == 0) {
                "[${v6Prefixes.random(random)}%02x]:$port".format(random.nextInt(FIRST_HOST, LAST_HOST + 1))
            } else {
                "${families[next++ % families.size]}${random.nextInt(FIRST_HOST, LAST_HOST + 1)}:$port"
            }
        }
        return result.toList()
    }

    const val V6_FAMILY = "v6"

    /**
     * Every address of a family's /24 is an endpoint (measured: 10 of 10 random hosts in each
     * family answered). The first ten are what every public config generator hands out.
     */
    private const val FIRST_HOST = 1
    private const val LAST_HOST = 254
    private const val MAX_DRAWS_PER_ENDPOINT = 20

    /** Routes in one family tend to share an exit, so results are judged per family. */
    fun familyOf(endpoint: String): String = when {
        endpoint.startsWith("[") -> V6_FAMILY
        endpoint.startsWith(HOST) -> "162.159.192." // what the hostname resolves to
        else -> endpoint.substringBeforeLast(':').substringBeforeLast('.') + "."
    }

    fun hostOf(endpoint: String): String {
        val separator = endpoint.lastIndexOf(':')
        require(separator > 0) { "endpoint must be host:port" }
        return endpoint.substring(0, separator).removePrefix("[").removeSuffix("]")
    }

    fun portOf(endpoint: String): Int = endpoint.substring(endpoint.lastIndexOf(':') + 1).toInt()

    /** An IPv4 or IPv6 address rather than a name: resolving it never touches DNS. */
    fun isLiteral(host: String): Boolean = ':' in host || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })

    fun resolve(endpoint: String): InetSocketAddress {
        val host = hostOf(endpoint)
        val port = portOf(endpoint)
        return try {
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (_: Exception) {
            InetSocketAddress.createUnresolved(host, port)
        }
    }
}

class EndpointScanner(
    private val probe: HandshakeProbe,
    /** Turns "host:port" into an address; the app passes one that looks names up outside the tunnel. */
    private val resolve: (String) -> InetSocketAddress = WarpEndpoints::resolve,
) {
    class Hit(val endpoint: String, val params: AwgParams, val rttMs: Long)

    /** Probes every endpoint with every obfuscation variant and returns those that answered, fastest first. */
    suspend fun scan(
        endpoints: List<String>,
        variants: List<AwgParams>,
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): List<Hit> = scanRoutes(endpoints.flatMap { endpoint -> variants.map { endpoint to it } }, onProgress)

    /** Probes specific endpoint and obfuscation pairs, such as routes remembered for this network. */
    suspend fun scanRoutes(
        routes: List<Pair<String, AwgParams>>,
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): List<Hit> {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        val done = AtomicInteger()
        coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            for ((endpoint, params) in routes) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val rtt = runCatching { probe.probe(resolve(endpoint), params, TIMEOUT_MS) }.getOrNull()
                        if (rtt != null) hits += Hit(endpoint, params, rtt)
                        onProgress(done.incrementAndGet(), routes.size, hits.size)
                    }
                }
            }
        }
        return synchronized(hits) { hits.sortedBy { it.rttMs } }
    }

    private companion object {
        const val PARALLELISM = 24
        const val TIMEOUT_MS = 1500
    }
}
