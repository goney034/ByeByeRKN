package com.dostup.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.dostup.app.awg.Obfuscation
import com.dostup.app.awg.SplitRule
import com.dostup.app.awg.WarpConfig
import com.dostup.app.data.Candidate
import com.dostup.app.data.Settings
import com.dostup.app.data.SplitMode
import com.dostup.app.data.Store
import com.dostup.app.probe.EndpointScanner
import com.dostup.app.probe.HandshakeProbe
import com.dostup.app.probe.TelegramProbe
import com.dostup.app.probe.WarpEndpoints
import com.dostup.app.split.AppCatalog
import com.dostup.app.warp.WarpAccount
import com.dostup.app.warp.WarpApi
import com.dostup.app.warp.WarpApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.Key
import java.io.BufferedReader
import java.io.StringReader
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

sealed interface VpnStatus {
    data object Disconnected : VpnStatus
    data class Working(val stage: String) : VpnStatus
    data class Connected(
        val endpoint: String,
        val obfuscation: Obfuscation?,
        val rttMs: Long?,
        val since: Long,
        /** False when the handshake worked but the traffic check through WARP did not. */
        val verified: Boolean,
        /** True when traffic flows but nothing better was found: it exits in Russia or Telegram doesn't answer. */
        val degraded: Boolean = false,
    ) : VpnStatus
    data class Failed(val message: String) : VpnStatus
}

data class Traffic(val rx: Long = 0, val tx: Long = 0)

class VpnController(private val context: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val backend by lazy { GoBackend(context) }
    private val backendLock = Any()
    private val api = WarpApi()
    // No connection reuse: after a tunnel swap an old connection may point at another data centre.
    private val traceClient = OkHttpClient.Builder()
        .callTimeout(TRACE_TIMEOUT_S, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()
    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()
    /** Where traffic currently leaves Cloudflare, from the last successful check. */
    private val _exit = MutableStateFlow<ExitInfo?>(null)
    val exit: StateFlow<ExitInfo?> = _exit.asStateFlow()

    private var connectJob: Job? = null
    private var monitorJob: Job? = null
    private var networkCheckJob: Job? = null
    private var retryJob: Job? = null
    private var retryDelayS = FIRST_RETRY_S
    private var tunnelUp = false
    /** The route currently carrying traffic, so a failover can skip it. */
    private var current: Candidate? = null
    private var lastAutoSearchAt = 0L
    private val stallDetector = StallDetector()
    private val networks = NetworkWatcher(context, ::onNetworkChanged)

    private val tunnel = object : Tunnel {
        // Shown as the session name in Android's VPN dialog; must match Tunnel.NAME_PATTERN.
        override fun getName() = "ByeByeRKN"

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) {
                tunnelUp = false
                // Revoked by the system or replaced by another VPN app: don't fight over it.
                if (_status.value is VpnStatus.Connected) {
                    monitorJob?.cancel()
                    current = null
                    store.wantConnected = false
                    _status.value = VpnStatus.Disconnected
                }
            }
        }
    }

    init {
        scope.launch { status.collect { Log.i(TAG, "status: $it") } }
        networks.start()
    }

    val awgVersion: String get() = runCatching { backend.version }.getOrDefault("?")

    fun connect() {
        if (connectJob?.isActive == true) return
        store.wantConnected = true
        retryJob?.cancel()
        connectJob = scope.launch { connectInternal() }
    }

    fun disconnect() {
        store.wantConnected = false
        retryJob?.cancel()
        networkCheckJob?.cancel()
        retryDelayS = FIRST_RETRY_S
        val previous = connectJob
        connectJob = scope.launch {
            previous?.cancelAndJoin()
            bringDown()
            _status.value = VpnStatus.Disconnected
        }
    }

    /** Re-applies settings (split tunnel, DNS, ...) by connecting again. */
    fun reconnect() {
        val previous = connectJob
        connectJob = scope.launch {
            previous?.cancelAndJoin()
            connectInternal()
        }
    }

    /** Forgets everything learned about routes, so the next connection searches from scratch. */
    fun forgetServer() {
        store.lastGood = null
        store.clearRouteBooks()
    }

    fun resetAccount() {
        store.account = null
        forgetServer()
    }

    /** Debug builds only: pretend the current route died, to exercise the failover path. */
    fun debugFailover() = failover("بررسی تعویض مسیر")

    /** The config the app would use right now, for sharing with other AmneziaWG clients. */
    suspend fun exportConfig(): String {
        store.customConfig?.let { return it }
        val account = ensureAccount()
        val settings = store.settings.value
        val candidate = store.lastGood?.takeIf { it.matches(settings) } ?: Candidate(
            settings.manualEndpoint ?: "162.159.192.1:2408",
            (settings.obfuscation ?: Obfuscation.QUIC).params(settings.quicSni),
            null,
        )
        return WarpConfig.render(
            account, candidate.endpoint, candidate.params, settings.dns.servers, splitRule(settings), candidate.mtu,
        )
    }

    private fun ensureAccount(): WarpAccount = store.account ?: run {
        _status.value = VpnStatus.Working("ثبت‌نام در WARP…")
        api.register(onRetry = { _status.value = VpnStatus.Working("ثبت‌نام در WARP: در حال امتحان مسیر جایگزین…") })
            .also {
                store.account = it
                store.lastRegisterAt = System.currentTimeMillis()
            }
    }

    /**
     * Connects from scratch, or, with [failed] set, replaces a dead route while the tunnel stays
     * up: probes bypass it via [GoBackend.protectSocket] and the new config swaps in place.
     */
    private suspend fun connectInternal(failed: Candidate? = null, mayReregister: Boolean = true) {
        monitorJob?.cancel()
        try {
            val settings = store.settings.value
            val split = splitRule(settings)
            store.customConfig?.let {
                connectCustom(it, split)
                return
            }

            val search = RouteSearch(ensureAccount(), settings, split, failed, networks.awaitKey())
            if (search.run()) return

            // Handshakes that fail everywhere can mean Cloudflare no longer accepts this key.
            if (!search.sawHandshake && mayReregister && reregister()) {
                connectInternal(failed, mayReregister = false)
                return
            }

            bringDown(keepService = store.wantConnected)
            if (networks.isCellular && looksLikeWhitelist()) {
                retryDelayS = MAX_RETRY_S
                failAndRetry("به نظر می‌رسد اپراتور فهرست سفید فعال کرده است: فقط سایت‌های مجاز باز هستند و VPN از این مسیر کار نمی‌کند.")
            } else {
                failAndRetry("هیچ سروری پاسخ نداد.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WarpApiException) {
            bringDown(keepService = store.wantConnected)
            failAndRetry(
                "ثبت‌نام در WARP ناموفق بود: ${e.message}. اگر API کلودفلر در شبکه‌ی شما در دسترس نیست، " +
                    "در تنظیمات یک کانفیگ آماده وارد کنید.",
            )
        } catch (e: BackendException) {
            Log.w(TAG, "backend error", e)
            bringDown()
            _status.value = VpnStatus.Failed(describe(e))
        } catch (e: IllegalStateException) {
            // Android refuses to start the service for background apps; only the user can restart from here.
            Log.w(TAG, "cannot start VPN service from background", e)
            bringDown()
            _status.value = VpnStatus.Failed("اندروید اجازه نداد VPN در پس‌زمینه روشن شود. روی «اتصال» بزنید.")
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            bringDown(keepService = store.wantConnected)
            failAndRetry(e.message ?: e.toString())
        }
    }

    private enum class Quality { GOOD, DEGRADED, FAILED }

    /**
     * One climb up the ladder: routes that were good on this network, then every disguise in turn
     * over the address families that haven't answered yet, then whatever worked at all.
     * A route is GOOD only if traffic flows, it doesn't exit in Russia and Telegram answers.
     */
    private inner class RouteSearch(
        private val account: WarpAccount,
        private val settings: Settings,
        private val split: SplitRule,
        private val failed: Candidate?,
        private val networkKey: String,
    ) {
        var sawHandshake = false
            private set
        private val book = store.routeBook(networkKey)
        /** Works with caveats, checked during this search. */
        private var degraded: Candidate? = null
        /** Worked with caveats some time ago: enough to stop trying known bad families for now. */
        private val fallback = book.knownFallback(now())?.takeIf { it.matches(settings) && !it.sameRoute(failed) }
        /** Answered a probe but left out because their family is known to be bad. */
        private val skipped = mutableListOf<Candidate>()
        private var handshakeOnly: Candidate? = null
        private var onTunnel: Candidate? = null
        private var mtuRetriesLeft = MTU_RETRY_CANDIDATES

        private fun now() = System.currentTimeMillis()

        suspend fun run(): Boolean {
            Log.i(TAG, "searching on network $networkKey, avoiding ${failed?.endpoint}")
            val protect = prepareProbeSockets()
            val probe = HandshakeProbe(Key.fromBase64(account.privateKey).bytes, Key.fromBase64(account.peerPublicKey).bytes, protect)
            val scanner = EndpointScanner(probe)

            // A probe tells in a moment which remembered routes still answer, without touching the tunnel.
            val remembered = book.knownGood(now()).filter { it.matches(settings) && !it.sameRoute(failed) }
            if (remembered.isNotEmpty()) {
                _status.value = VpnStatus.Working("در حال بررسی مسیرهای شناخته‌شده…")
                val answered = scanner.scanRoutes(remembered.map { it.endpoint to it.params }).map { it.endpoint }
                for (route in remembered) {
                    if (route.endpoint !in answered) {
                        failedRoute(route)
                    } else if (attempt(route) == Quality.GOOD) {
                        return true
                    }
                }
            }

            val endpoints = settings.manualEndpoint?.let(::listOf) ?: WarpEndpoints.sample(SCAN_ENDPOINTS)
            val masks = settings.obfuscation?.let(::listOf) ?: Obfuscation.autoOrder
            val reached = HashSet<String>()

            for ((index, mask) in masks.withIndex()) {
                val remaining = endpoints.filter { WarpEndpoints.familyOf(it) !in reached }
                if (remaining.isEmpty()) break
                // With a working route in hand, the heavy disguises aren't worth the battery.
                if (degraded != null && index >= MASKS_WITH_FALLBACK) break
                val hits = scanner.scan(remaining, listOf(mask.params(settings.quicSni))) { done, total, found ->
                    _status.value = VpnStatus.Working("جست‌وجوی سرور (${mask.title}): $done از $total، پاسخ‌دهنده: $found")
                }
                reached += hits.map { WarpEndpoints.familyOf(it.endpoint) }
                val candidates = hits.map { Candidate(it.endpoint, it.params, it.rttMs) }.filterNot { it.sameRoute(failed) }
                for (candidate in book.attemptOrder(candidates, now())) {
                    // A family known to exit badly is only worth a try while there is nothing at all.
                    if ((degraded ?: fallback) != null && book.isBadFamily(candidate.endpoint, now())) {
                        skipped += candidate
                        continue
                    }
                    if (attempt(candidate) == Quality.GOOD) return true
                }
            }

            // Probes may be filtered while the tunnel itself works: try a few blindly.
            if (reached.isEmpty() && (degraded ?: fallback) == null) {
                val mask = settings.obfuscation ?: Obfuscation.QUIC
                for (endpoint in WarpEndpoints.sample(BLIND_ATTEMPTS).take(BLIND_ATTEMPTS)) {
                    if (attempt(Candidate(endpoint, mask.params(settings.quicSni), null)) == Quality.GOOD) return true
                }
            }

            return settle()
        }

        /** Nothing fully good: use the best thing found rather than leave the user without a VPN. */
        private suspend fun settle(): Boolean {
            for (route in listOfNotNull(degraded, fallback) + skipped) {
                if (onTunnel?.sameRoute(route) == true || tryConfig(render(route, route.mtu), verify = true) == Outcome.OK) {
                    // Routing changes, so a route remembered as bad may have become good.
                    val good = routeIsGood()
                    book.recordSuccess(route, good, now())
                    store.saveRouteBook(networkKey, book)
                    onConnected(route, verified = true, degraded = !good)
                    return true
                }
            }
            handshakeOnly?.let { route ->
                if (tryConfig(render(route, route.mtu), verify = false) == Outcome.OK) {
                    onConnected(route, verified = false, degraded = true)
                    return true
                }
            }
            return false
        }

        private fun render(candidate: Candidate, mtu: Int) =
            WarpConfig.render(account, candidate.endpoint, candidate.params, settings.dns.servers, split, mtu)

        private suspend fun attempt(candidate: Candidate): Quality {
            val steps = if (mtuRetriesLeft > 0) (listOf(candidate.mtu) + WarpConfig.MTU_STEPS).distinct() else listOf(candidate.mtu)
            for ((index, mtu) in steps.withIndex()) {
                val note = if (index > 0) " · MTU $mtu" else ""
                _status.value = VpnStatus.Working("در حال اتصال: ${candidate.endpoint} · ${candidate.params.obfuscation.title}$note")
                val route = candidate.withMtu(mtu)
                val outcome = tryConfig(render(candidate, mtu), verify = true)
                onTunnel = route
                when (outcome) {
                    // A smaller MTU cannot help a handshake that never happened.
                    Outcome.NO_HANDSHAKE -> return failedRoute(candidate)
                    Outcome.HANDSHAKE_ONLY -> {
                        sawHandshake = true
                        if (handshakeOnly == null) handshakeOnly = route
                    }
                    Outcome.OK -> {
                        sawHandshake = true
                        val good = routeIsGood()
                        Log.i(TAG, "route ${candidate.endpoint} ${candidate.params.obfuscation} mtu=$mtu: exit=${_exit.value?.colo} good=$good")
                        book.recordSuccess(route, good, now())
                        store.saveRouteBook(networkKey, book)
                        if (good) {
                            onConnected(route, verified = true, degraded = false)
                            return Quality.GOOD
                        }
                        if (degraded == null) degraded = route
                        return Quality.DEGRADED
                    }
                }
            }
            if (steps.size > 1) mtuRetriesLeft--
            return failedRoute(candidate)
        }

        private fun failedRoute(candidate: Candidate): Quality {
            book.recordFailure(candidate)
            store.saveRouteBook(networkKey, book)
            return Quality.FAILED
        }
    }

    /** The last trace is fresh here, since a traffic check has just passed on this route. */
    private suspend fun routeIsGood(): Boolean {
        val exit = _exit.value ?: return false
        if (exit.inRussia) return false
        val network = awaitVpnNetwork() ?: return false
        return TelegramProbe.answers(network.socketFactory)
    }

    private fun reregister(): Boolean {
        val now = System.currentTimeMillis()
        if (now - store.lastRegisterAt < REREGISTER_EVERY_MS) return false
        store.lastRegisterAt = now
        _status.value = VpnStatus.Working("در حال به‌روزرسانی ثبت‌نام WARP…")
        return runCatching { api.register() }
            .onSuccess {
                Log.i(TAG, "registered a new WARP account")
                store.account = it
                forgetServer()
            }
            .isSuccess
    }

    /** Only allowed sites open on mobile: Yandex answers, while nothing foreign does. */
    private fun looksLikeWhitelist(): Boolean {
        fun reachable(ip: String) = runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, 443), WHITELIST_CHECK_TIMEOUT_MS) }
        }.isSuccess
        return reachable("77.88.55.88") && !reachable("1.1.1.1") && !reachable("8.8.8.8")
    }

    /** Shows the error and, if the user still wants the VPN, tries again with growing pauses. */
    private fun failAndRetry(message: String) {
        if (!store.wantConnected) {
            _status.value = VpnStatus.Failed(message)
            return
        }
        val delayS = retryDelayS
        retryDelayS = (retryDelayS * 2).coerceAtMost(MAX_RETRY_S)
        _status.value = VpnStatus.Failed("$message تلاش مجدد پس از ${formatSeconds(delayS)}.")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayS * 1000L)
            if (store.wantConnected && _status.value is VpnStatus.Failed) connect()
        }
    }

    /** Automatic searches swap tunnels and scan, so they are spaced out and skipped on a hot phone. */
    private fun failover(reason: String) {
        if (connectJob?.isActive == true || _status.value !is VpnStatus.Connected) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSearchAt < MIN_AUTO_SEARCH_GAP_MS || tooHot()) {
            Log.i(TAG, "failover postponed: $reason")
            return
        }
        lastAutoSearchAt = now
        val failed = current
        Log.i(TAG, "failover: $reason (was ${failed?.endpoint} ${failed?.params?.obfuscation})")
        _status.value = VpnStatus.Working("ارتباط قطع شد ($reason)، در حال جست‌وجوی سرور دیگر…")
        connectJob = scope.launch { connectInternal(failed) }
    }

    private fun tooHot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    /**
     * Reconnects on the new network. Keeping the old session would send plain WireGuard data with
     * no disguise until the next rekey; a fresh handshake starts with the mask, and the routes
     * tried first are those that worked on this network before.
     */
    private fun onNetworkChanged(label: String) {
        stallDetector.reset()
        networkCheckJob?.cancel()
        networkCheckJob = scope.launch {
            delay(NETWORK_SETTLE_MS)
            if (!store.wantConnected) return@launch
            when (_status.value) {
                is VpnStatus.Connected, is VpnStatus.Working -> {
                    val previous = connectJob
                    _status.value = VpnStatus.Working("شبکه تغییر کرد ($label)، در حال اتصال…")
                    connectJob = scope.launch {
                        previous?.cancelAndJoin()
                        connectInternal()
                    }
                }
                // A pending retry should not wait out its pause once a network shows up.
                is VpnStatus.Failed -> {
                    retryDelayS = FIRST_RETRY_S
                    connect()
                }
                VpnStatus.Disconnected -> Unit
            }
        }
    }

    private suspend fun connectCustom(text: String, split: SplitRule) {
        _status.value = VpnStatus.Working("در حال اتصال با کانفیگ سفارشی…")
        if (tryConfig(WarpConfig.applySplit(text, split), verify = false) == Outcome.OK) {
            _status.value = VpnStatus.Connected(WarpConfig.endpointOf(text) ?: "کانفیگ سفارشی", null, null, System.currentTimeMillis(), false)
            startMonitor(checkQuality = false)
        } else {
            bringDown(keepService = store.wantConnected)
            failAndRetry("سرور کانفیگ واردشده به handshake پاسخ نداد.")
        }
    }

    /** Returns how probe sockets get out of the tunnel; closes the tunnel if they can't. */
    private suspend fun prepareProbeSockets(): (DatagramSocket) -> Unit {
        if (!tunnelUp) return {}
        val canProtect = runCatching { DatagramSocket().use { GoBackend.protectSocket(it) } }.getOrDefault(false)
        if (canProtect) return { GoBackend.protectSocket(it) }
        bringDown(keepService = true)
        return {}
    }

    private enum class Outcome { OK, HANDSHAKE_ONLY, NO_HANDSHAKE }

    private suspend fun tryConfig(text: String, verify: Boolean): Outcome {
        val config = Config.parse(BufferedReader(StringReader(text)))
        // Rapid swaps can hit "Cannot set address" while Android still tears down the old interface.
        for (attempt in 1..ESTABLISH_TRIES) {
            try {
                synchronized(backendLock) {
                    backend.setState(tunnel, Tunnel.State.UP, config)
                    tunnelUp = true
                }
                break
            } catch (e: BackendException) {
                throw e
            } catch (e: Exception) {
                if (attempt == ESTABLISH_TRIES) throw e
                Log.w(TAG, "establish failed (${e.message}), retrying")
                delay(ESTABLISH_RETRY_MS)
            }
        }
        if (!waitForHandshake()) return Outcome.NO_HANDSHAKE
        if (!verify) return Outcome.OK
        return if (trafficGoesThroughWarp()) Outcome.OK else Outcome.HANDSHAKE_ONLY
    }

    private suspend fun waitForHandshake(): Boolean {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val handshake = synchronized(backendLock) { backend.getLastHandshake(tunnel) }
            if (handshake > 0) return true
            if (handshake == -3L) return false
            delay(250)
        }
        return false
    }

    /**
     * Our own process is always routed through the tunnel, so Cloudflare's trace shows warp=on.
     * The TLS handshake to cloudflare.com also needs full-size packets, which makes this an MTU check.
     */
    private suspend fun trafficGoesThroughWarp(): Boolean {
        val info = currentExit() ?: return false
        if (!info.warp) return false
        if (info != _exit.value) Log.i(TAG, "exit: ${info.colo} ${info.country}")
        _exit.value = info
        return true
    }

    /**
     * Asks Cloudflare where our traffic leaves, over sockets bound to the VPN network. Unbound
     * sockets can slip out through the physical network while Android switches tunnels, which
     * reads as warp=off with our real address; that case gets one more try.
     */
    private suspend fun currentExit(): ExitInfo? {
        var info: ExitInfo? = null
        for (attempt in 1..TRACE_TRIES) {
            val network = awaitVpnNetwork() ?: return null
            val started = System.currentTimeMillis()
            info = traceVia(network)
            if (info?.warp == true) return info
            // A slow failure is a real one; a fast one is the old VPN network going away.
            if (info == null && System.currentTimeMillis() - started > FAST_FAILURE_MS) return null
            delay(TRACE_RETRY_MS)
        }
        return info
    }

    private fun traceVia(network: Network): ExitInfo? = runCatching {
        val client = traceClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .build()
        val request = Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) ExitInfo.parse(response.body?.string().orEmpty()) else null
        }
    }.getOrNull()

    private suspend fun awaitVpnNetwork(): Network? {
        val deadline = System.currentTimeMillis() + VPN_NETWORK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION") // allNetworks is the simplest way to list current networks
            val vpn = connectivity.allNetworks.firstOrNull {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpn != null) return vpn
            delay(100)
        }
        return null
    }

    /** Debug builds only: measures Telegram and download speed through the running tunnel. */
    fun debugDiagnose() {
        scope.launch {
            val tag = "Dostup/Diag"
            val network = awaitVpnNetwork()
            if (network == null) {
                Log.i(tag, "no VPN network")
                Log.i(tag, "done")
                return@launch
            }
            val route = current?.let { "${it.endpoint} ${it.params.obfuscation} mtu=${it.mtu}" }
            Log.i(tag, "route: $route on ${networks.currentKey()}, exit: ${traceVia(network)?.let { "${it.colo} ${it.country} ${it.ip}" }}")
            for (dc in TelegramProbe.dcs) {
                for (address in listOf(dc.v4, dc.v6)) {
                    val r = TelegramProbe.check(address, network.socketFactory)
                    Log.i(tag, "telegram DC${dc.id} $address: connect=${r.connectMs} reply=${r.replyMs} ${r.error.orEmpty()}")
                }
            }
            for ((name, url) in DIAG_DOWNLOADS) Log.i(tag, "speed $name: ${measureDownload(network, url)}")
            Log.i(tag, "done")
        }
    }

    private fun measureDownload(network: Network, url: String): String = runCatching {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .callTimeout(DIAG_DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val start = System.nanoTime()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val source = response.body?.byteStream() ?: return@use "no body"
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            var firstByteMs = -1L
            try {
                while (total < DIAG_DOWNLOAD_BYTES) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (firstByteMs < 0) firstByteMs = (System.nanoTime() - start) / 1_000_000
                    total += n
                }
            } catch (e: Exception) {
                Log.i("Dostup/Diag", "download stopped: $e")
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            "HTTP ${response.code}, ${total / 1024} KB in $ms ms (first byte $firstByteMs ms), ${if (ms > 0) total * 8 / ms else 0} kbit/s"
        }
    }.getOrElse { "failed: $it" }

    /**
     * Debug builds only: connects through one endpoint of every WARP address family in turn and
     * logs where traffic leaves Cloudflare, to learn whether the exit depends on the endpoint.
     */
    fun debugSurvey() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            monitorJob?.cancel()
            val account = store.account ?: return@launch
            val settings = store.settings.value
            val split = splitRule(settings)
            val params = current?.params ?: Obfuscation.QUIC.params()
            val port = current?.endpoint?.substringAfterLast(':') ?: "500"
            val endpoints = listOf(
                "162.159.192.", "162.159.195.", "188.114.96.", "188.114.98.", "8.6.112.",
                "8.34.70.", "8.35.211.", "8.39.125.", "8.47.69.",
            ).map { "$it${(1..10).random()}:$port" } + "[2606:4700:d0::a29f:c001]:$port"
            val dc = TelegramProbe.dcs[1]
            for (endpoint in endpoints) {
                _status.value = VpnStatus.Working("اندازه‌گیری خروجی: $endpoint")
                val text = WarpConfig.render(account, endpoint, params, settings.dns.servers, split)
                val outcome = runCatching { tryConfig(text, verify = false) }.getOrNull()
                val exit = if (outcome == Outcome.OK) currentExit() else null
                val network = if (exit != null) awaitVpnNetwork() else null
                val v4 = network?.let { TelegramProbe.check(dc.v4, it.socketFactory, SURVEY_TELEGRAM_TIMEOUT_MS).replyMs }
                val v6 = network?.let { TelegramProbe.check(dc.v6, it.socketFactory, SURVEY_TELEGRAM_TIMEOUT_MS).replyMs }
                val verdict = exit?.let { "${it.colo} ${it.country} warp=${it.warp} telegram v4=$v4 v6=$v6" } ?: "no handshake"
                Log.i(TAG, "survey $endpoint -> $verdict")
                delay(SURVEY_PAUSE_MS) // gentle on the phone: it overheated when this ran flat out
            }
            Log.i(TAG, "survey finished")
            connectInternal()
        }
    }

    private fun onConnected(candidate: Candidate, verified: Boolean, degraded: Boolean) {
        store.lastGood = candidate
        current = candidate
        retryDelayS = FIRST_RETRY_S
        _status.value = VpnStatus.Connected(
            candidate.endpoint, candidate.params.obfuscation, candidate.rttMs, System.currentTimeMillis(), verified, degraded,
        )
        startMonitor(checkQuality = !degraded)
    }

    /**
     * Publishes traffic counters every second and watches the session: dead sessions by counters,
     * degraded ones by an occasional real check while traffic flows. An idle phone does nothing.
     */
    private fun startMonitor(checkQuality: Boolean) {
        monitorJob?.cancel()
        stallDetector.reset()
        monitorJob = scope.launch {
            var tick = 0
            var quietUntil = System.currentTimeMillis() + MONITOR_GRACE_MS
            var nextQualityCheckAt = System.currentTimeMillis() + QUALITY_CHECK_EVERY_MS
            var rxAtLastCheck = 0L
            while (isActive) {
                delay(1000)
                val stats = synchronized(backendLock) { runCatching { backend.getStatistics(tunnel) }.getOrNull() } ?: continue
                _traffic.value = Traffic(stats.totalRx(), stats.totalTx())
                if (++tick % MONITOR_EVERY_S != 0) continue

                val now = System.currentTimeMillis()
                val handshake = synchronized(backendLock) { runCatching { backend.getLastHandshake(tunnel) }.getOrDefault(0L) }
                val suspicion = stallDetector.update(now, stats.totalRx(), stats.totalTx(), handshake.coerceAtLeast(0))
                if (suspicion != null && now >= quietUntil) {
                    // Counters only raise a suspicion; a real request through the tunnel decides.
                    Log.i(TAG, "session suspicious: $suspicion")
                    if (trafficGoesThroughWarp()) {
                        stallDetector.reset()
                        quietUntil = System.currentTimeMillis() + MONITOR_GRACE_MS
                    } else {
                        failover(suspicion)
                        quietUntil = System.currentTimeMillis() + MONITOR_GRACE_MS
                    }
                    continue
                }

                if (checkQuality && now >= nextQualityCheckAt) {
                    nextQualityCheckAt = now + QUALITY_CHECK_EVERY_MS
                    val flowing = stats.totalRx() - rxAtLastCheck >= QUALITY_CHECK_MIN_RX
                    rxAtLastCheck = stats.totalRx()
                    if (flowing && !(trafficGoesThroughWarp() && routeIsGood())) {
                        failover("مسیر بدتر شد")
                    }
                }
            }
        }
    }

    /**
     * Closes the tunnel. With [keepService] the VpnService stays alive for an automatic retry;
     * otherwise it stops, as it should when the user disconnects.
     */
    private suspend fun bringDown(keepService: Boolean = false) {
        monitorJob?.cancel()
        current = null
        _exit.value = null
        val wasUp = synchronized(backendLock) {
            val up = tunnelUp
            runCatching {
                if (keepService) {
                    backend.closeTunnelKeepService(tunnel)
                } else {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    // A failed search may have left the service running with no tunnel.
                    GoBackend.stopIdleService()
                }
            }
            tunnelUp = false
            up
        }
        // Give a stopped VpnService time to be destroyed before anything brings it up again.
        if (wasUp && !keepService) delay(SERVICE_SETTLE_MS)
        _traffic.value = Traffic()
    }

    private fun splitRule(settings: Settings): SplitRule = when (settings.splitMode) {
        SplitMode.ALL -> SplitRule()
        SplitMode.EXCLUDE -> SplitRule(excluded = AppCatalog.installedOnly(context, settings.excludedApps - context.packageName))
        SplitMode.INCLUDE -> SplitRule(included = AppCatalog.installedOnly(context, settings.includedApps + context.packageName))
    }

    private fun Candidate.matches(settings: Settings) =
        (settings.obfuscation == null || params.obfuscation == settings.obfuscation) &&
            (settings.manualEndpoint == null || endpoint == settings.manualEndpoint)

    private fun describe(e: BackendException): String = when (e.reason) {
        BackendException.Reason.VPN_NOT_AUTHORIZED -> "مجوز VPN داده نشده است. دوباره روی «اتصال» بزنید و اتصال را مجاز کنید."
        BackendException.Reason.UNABLE_TO_START_VPN -> "سیستم سرویس VPN را راه‌اندازی نکرد. دوباره تلاش کنید."
        BackendException.Reason.TUN_CREATION_ERROR -> "ساخت رابط VPN ناموفق بود. شاید VPN دیگری یا «VPN همیشه روشن» برنامه‌ی دیگری فعال است."
        BackendException.Reason.DNS_RESOLUTION_FAILURE -> "پیدا کردن آدرس سرور ناموفق بود: ${e.format.firstOrNull() ?: ""}."
        BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "تونل راه‌اندازی نشد (کد ${e.format.firstOrNull() ?: "?"})."
        else -> "خطای VPN: ${e.reason}"
    }

    private fun formatSeconds(seconds: Int) = if (seconds < 60) "$seconds ثانیه" else "${seconds / 60} دقیقه"

    private companion object {
        const val TAG = "Dostup/Vpn"
        const val SCAN_ENDPOINTS = 32
        const val BLIND_ATTEMPTS = 3
        const val MASKS_WITH_FALLBACK = 3
        /** How many candidates may spend time on MTU fallback before we move on. */
        const val MTU_RETRY_CANDIDATES = 2
        const val HANDSHAKE_TIMEOUT_MS = 6_000L
        const val TRACE_TIMEOUT_S = 8L
        const val TRACE_TRIES = 2
        const val TRACE_RETRY_MS = 1_000L
        const val FAST_FAILURE_MS = 2_000L
        const val VPN_NETWORK_WAIT_MS = 3_000L
        const val ESTABLISH_TRIES = 3
        const val ESTABLISH_RETRY_MS = 400L
        const val SERVICE_SETTLE_MS = 800L
        const val NETWORK_SETTLE_MS = 2_000L
        const val MONITOR_EVERY_S = 5
        /** No verdicts right after connecting or after a passed check, to avoid flapping. */
        const val MONITOR_GRACE_MS = 30_000L
        const val QUALITY_CHECK_EVERY_MS = 3 * 60_000L
        const val QUALITY_CHECK_MIN_RX = 64 * 1024L
        const val MIN_AUTO_SEARCH_GAP_MS = 60_000L
        const val REREGISTER_EVERY_MS = 6 * 3_600_000L
        const val WHITELIST_CHECK_TIMEOUT_MS = 2_500
        const val FIRST_RETRY_S = 30
        const val MAX_RETRY_S = 300
        const val SURVEY_PAUSE_MS = 3_000L
        const val SURVEY_TELEGRAM_TIMEOUT_MS = 3_000
        const val DIAG_DOWNLOAD_BYTES = 1_000_000L
        const val DIAG_DOWNLOAD_TIMEOUT_S = 20L
        val DIAG_DOWNLOADS = listOf(
            "cloudflare" to "https://speed.cloudflare.com/__down?bytes=1000000",
            "google" to "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
            "telegram" to "https://telegram.org/dl/android/apk",
        )
    }
}
