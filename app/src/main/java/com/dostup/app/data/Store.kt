package com.dostup.app.data

import android.content.Context
import androidx.annotation.StringRes
import com.dostup.app.R
import com.dostup.app.awg.AwgParams
import com.dostup.app.awg.Obfuscation
import com.dostup.app.awg.hexToBytes
import com.dostup.app.split.AppCatalog
import com.dostup.app.warp.MasqueAccount
import com.dostup.app.warp.WarpAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class SplitMode { ALL, EXCLUDE, INCLUDE }

enum class DnsOption(@StringRes val titleRes: Int, val servers: List<String>) {
    CLOUDFLARE(R.string.dns_cloudflare, listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE(R.string.dns_google, listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")),
    ADGUARD(R.string.dns_adguard, listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")),
    QUAD9(R.string.dns_quad9, listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")),
}

data class Settings(
    /** null means "pick automatically". */
    val obfuscation: Obfuscation? = null,
    /** null means "scan for the best endpoint". */
    val manualEndpoint: String? = null,
    val dns: DnsOption = DnsOption.CLOUDFLARE,
    val splitMode: SplitMode = SplitMode.EXCLUDE,
    val excludedApps: Set<String> = AppCatalog.russianServices,
    val includedApps: Set<String> = AppCatalog.blockedServices,
    /** SNI for the QUIC mask; blank means a random popular site. */
    val quicSni: String = "",
    /** A proxy for devices on the phone's hotspot, while the VPN is connected. */
    val shareVpn: Boolean = false,
)

/** An endpoint plus obfuscation that is known to work (or worth trying). */
class Candidate(val endpoint: String, val params: AwgParams, val rttMs: Long?, val mtu: Int = DEFAULT_MTU) {
    fun withMtu(mtu: Int) = Candidate(endpoint, params, rttMs, mtu)

    fun sameRoute(other: Candidate?) =
        other != null && other.endpoint == endpoint && other.params.obfuscation == params.obfuscation

    fun toJson(): String = JSONObject()
        .put("endpoint", endpoint)
        .put("obfuscation", params.obfuscation.name)
        .put("jc", params.jc).put("jmin", params.jmin).put("jmax", params.jmax)
        .put("i1", params.i1Hex())
        .put("rtt", rttMs ?: -1)
        .put("mtu", mtu)
        .toString()

    companion object {
        const val DEFAULT_MTU = 1280

        fun fromJson(json: String): Candidate = JSONObject(json).run {
            val obfuscation = Obfuscation.valueOf(getString("obfuscation"))
            // Entries saved before junk sizes were randomized only have the preset name.
            val fresh = obfuscation.params()
            val params = AwgParams(
                obfuscation,
                optInt("jc", fresh.jc),
                optInt("jmin", fresh.jmin),
                optInt("jmax", fresh.jmax),
                optString("i1").takeIf { it.isNotEmpty() && it != "null" }?.hexToBytes() ?: fresh.i1,
            )
            Candidate(getString("endpoint"), params, getLong("rtt").takeIf { it >= 0 }, optInt("mtu", DEFAULT_MTU))
        }
    }
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("dostup", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("obfuscation", next.obfuscation?.name)
            .putString("manualEndpoint", next.manualEndpoint)
            .putString("dns", next.dns.name)
            .putString("splitMode", next.splitMode.name)
            .putStringSet("excludedApps", next.excludedApps)
            .putStringSet("includedApps", next.includedApps)
            .putString("quicSni", next.quicSni)
            .putBoolean("shareVpn", next.shareVpn)
            .apply()
        _settings.value = next
    }

    var account: WarpAccount?
        get() = prefs.getString("account", null)?.let { runCatching { WarpAccount.fromJson(it) }.getOrNull() }
        set(value) = prefs.edit().putString("account", value?.toJson()).apply()

    /** A WARP device of its own for MASQUE, registered the first time MASQUE is needed. */
    var masqueAccount: MasqueAccount?
        get() = prefs.getString("masqueAccount", null)?.let { runCatching { MasqueAccount.fromJson(it) }.getOrNull() }
        set(value) = prefs.edit().putString("masqueAccount", value?.toJson()).apply()

    var lastGood: Candidate?
        get() = prefs.getString("lastGood", null)?.let { runCatching { Candidate.fromJson(it) }.getOrNull() }
        set(value) = prefs.edit().putString("lastGood", value?.toJson()).apply()

    /** Routes learned on one network; [key] comes from NetworkWatcher. */
    fun routeBook(key: String): RouteBook =
        prefs.getString(ROUTES_PREFIX + key, null)?.let(RouteBook::fromJson) ?: RouteBook()

    fun saveRouteBook(key: String, book: RouteBook) {
        prefs.edit().putString(ROUTES_PREFIX + key, book.toJson()).apply()
    }

    fun clearRouteBooks() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(ROUTES_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(value) = prefs.edit().putBoolean("askedNotifications", value).apply()

    /** When the app last registered with WARP, to limit automatic re-registration. */
    var lastRegisterAt: Long
        get() = prefs.getLong("lastRegisterAt", 0)
        set(value) = prefs.edit().putLong("lastRegisterAt", value).apply()

    /** A WARP+ key, bound to every WARP device the app registers. */
    var warpPlusKey: String?
        get() = prefs.getString("warpPlusKey", null)
        set(value) = prefs.edit().putString("warpPlusKey", value).apply()

    /** A newer, signed list of addresses and disguise sites than the built-in one. */
    var catalogJson: String?
        get() = prefs.getString("catalogJson", null)
        set(value) = prefs.edit().putString("catalogJson", value).apply()

    var catalogCheckedAt: Long
        get() = prefs.getLong("catalogCheckedAt", 0)
        set(value) = prefs.edit().putLong("catalogCheckedAt", value).apply()

    /** When registering for MASQUE was last tried, so a blocked API doesn't slow every search. */
    var masqueRegisterTriedAt: Long
        get() = prefs.getLong("masqueRegisterTriedAt", 0)
        set(value) = prefs.edit().putLong("masqueRegisterTriedAt", value).apply()

    /** A user-imported .conf that replaces the WARP account entirely. */
    var customConfig: String?
        get() = prefs.getString("customConfig", null)
        set(value) = prefs.edit().putString("customConfig", value).apply()

    var wantConnected: Boolean
        get() = prefs.getBoolean("wantConnected", false)
        set(value) = prefs.edit().putBoolean("wantConnected", value).apply()

    /** Kept in sync by the tile's onTileAdded/onTileRemoved; Android has no query for it. */
    var tileAdded: Boolean
        get() = prefs.getBoolean("tileAdded", false)
        set(value) = prefs.edit().putBoolean("tileAdded", value).apply()

    /** The newest version seen on GitHub. */
    var latestVersion: String?
        get() = prefs.getString("latestVersion", null)
        set(value) = prefs.edit().putString("latestVersion", value).apply()

    var lastUpdateCheckAt: Long
        get() = prefs.getLong("lastUpdateCheckAt", 0)
        set(value) = prefs.edit().putLong("lastUpdateCheckAt", value).apply()

    private fun loadSettings(): Settings {
        val defaults = Settings()
        return Settings(
            obfuscation = prefs.getString("obfuscation", null)?.let { runCatching { Obfuscation.valueOf(it) }.getOrNull() },
            manualEndpoint = prefs.getString("manualEndpoint", null),
            dns = prefs.getString("dns", null)?.let { runCatching { DnsOption.valueOf(it) }.getOrNull() } ?: defaults.dns,
            splitMode = prefs.getString("splitMode", null)?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() } ?: defaults.splitMode,
            excludedApps = prefs.getStringSet("excludedApps", null)?.toSet() ?: defaults.excludedApps,
            includedApps = prefs.getStringSet("includedApps", null)?.toSet() ?: defaults.includedApps,
            quicSni = prefs.getString("quicSni", null) ?: defaults.quicSni,
            shareVpn = prefs.getBoolean("shareVpn", defaults.shareVpn),
        )
    }

    private companion object {
        const val ROUTES_PREFIX = "routes:"
    }
}
