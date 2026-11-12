package com.dostup.app.update

import android.util.Log
import com.dostup.app.data.Store
import com.dostup.app.probe.EndpointCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches catalog/warp.json and its signature from the repository at most once a day, and uses
 * it when the signature checks out and the version is newer than what the app has. Without
 * network, or with anything wrong, the app keeps the list it has.
 */
class CatalogUpdater(private val store: Store, private val repo: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    init {
        // A catalog saved earlier was verified when it arrived.
        store.catalogJson?.let(EndpointCatalog::fromJson)
            ?.takeIf { it.version > EndpointCatalog.BUILT_IN.version }
            ?.let { EndpointCatalog.current = it }
    }

    suspend fun refreshIfDue() {
        if (repo.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - store.catalogCheckedAt < CHECK_EVERY_MS) return
        val catalog = withContext(Dispatchers.IO) { fetch() } ?: return
        store.catalogCheckedAt = now
        if (catalog.first.version <= EndpointCatalog.current.version) return
        store.catalogJson = catalog.second
        EndpointCatalog.current = catalog.first
        Log.i(TAG, "catalog ${catalog.first.version}: ${catalog.first.prefixes.size} families, ${catalog.first.ports.size} ports")
    }

    /** The catalog and its text, or null on any failure, including a bad signature. */
    private fun fetch(): Pair<EndpointCatalog, String>? = runCatching {
        val json = get("$BASE/$repo/$BRANCH/$PATH")
        val signature = get("$BASE/$repo/$BRANCH/$PATH.sig")
        if (!EndpointCatalog.verify(json.toByteArray(), signature)) {
            Log.w(TAG, "catalog signature does not match, ignoring it")
            return null
        }
        EndpointCatalog.fromJson(json)?.let { it to json }
    }.onFailure { Log.i(TAG, "catalog check failed: ${it.message}") }.getOrNull()

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }
        it.body?.string().orEmpty()
    }

    private companion object {
        const val TAG = "Dostup/Catalog"
        const val BASE = "https://raw.githubusercontent.com"
        const val BRANCH = "main"
        const val PATH = "catalog/warp.json"
        const val CALL_TIMEOUT_S = 20L
        const val CHECK_EVERY_MS = 24 * 3600 * 1000L
    }
}
