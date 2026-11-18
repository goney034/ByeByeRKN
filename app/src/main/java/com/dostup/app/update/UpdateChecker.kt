package com.dostup.app.update

import android.util.Log
import com.dostup.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tells the user about new releases on GitHub. New blocks can only be answered with an update,
 * and an APK installed by hand gets no updates on its own.
 *
 * This reads the redirect of the public /releases/latest page rather than GitHub's API: the API
 * allows few anonymous requests per IP address, and a WARP exit address is shared with many people.
 * Note that GitHub's "latest" leaves out releases marked as pre-release.
 */
class UpdateChecker(private val store: Store, private val repo: String, private val installed: String) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val _newVersion = MutableStateFlow(store.latestVersion?.let(::ifNewer))

    /** A release newer than the installed version, if one is known. */
    val newVersion: StateFlow<String?> = _newVersion

    val releasesUrl: String get() = "https://github.com/$repo/releases/latest"

    /** Called when the app opens, so it never wakes the phone. A failed check runs again next time. */
    suspend fun checkIfDue() {
        if (repo.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - store.lastUpdateCheckAt < CHECK_EVERY_MS) return
        val latest = withContext(Dispatchers.IO) { fetchLatest() } ?: return
        store.lastUpdateCheckAt = now
        store.latestVersion = latest
        _newVersion.value = ifNewer(latest)
    }

    private fun ifNewer(version: String) = version.takeIf { repo.isNotEmpty() && ReleaseVersions.isNewer(it, installed) }

    private fun fetchLatest(): String? = runCatching {
        client.newCall(Request.Builder().url(releasesUrl).build()).execute().use {
            ReleaseVersions.fromRedirect(it.header("Location"))
        }
    }.onFailure { Log.i(TAG, "update check failed: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "Dostup/Update"
        const val CALL_TIMEOUT_S = 20L
        const val CHECK_EVERY_MS = 3 * 3600 * 1000L // fork: a new release shows up within hours, not a day
    }
}

internal object ReleaseVersions {
    private const val TAG_PATH = "/releases/tag/"
    private val NUMBERS = Regex("""\d+(\.\d+)*""")

    /** GitHub redirects /releases/latest to /releases/tag/<tag>, or elsewhere if there is no release. */
    fun fromRedirect(location: String?): String? =
        location?.substringAfter(TAG_PATH, "")?.takeIf { it.isNotEmpty() }?.removePrefix("v")

    fun isNewer(candidate: String, installed: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Long> =
        NUMBERS.find(version)?.value?.split('.')?.map { it.toLongOrNull() ?: 0 } ?: emptyList()
}
