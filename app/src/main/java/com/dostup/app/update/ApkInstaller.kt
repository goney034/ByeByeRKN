package com.dostup.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dostup.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fork: downloads the new release inside the app and hands it to the system installer.
 * The file comes from this fork's own release (ByeByeRKN-fa-v<version>.apk, with a .sha256 next to it).
 * Android only accepts it if it is signed with the same key as the installed app.
 */
object ApkInstaller {
    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data class Ready(val file: File) : State
        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).readTimeout(30, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Tap on the update chip: download when nothing is downloaded yet, install when the file is ready. */
    fun onTap(context: Context, repo: String, version: String) {
        val app = context.applicationContext
        when (val s = _state.value) {
            is State.Downloading -> Unit
            is State.Ready -> install(app, s.file)
            else -> {
                _state.value = State.Downloading(0)
                scope.launch { download(app, repo, version) }
            }
        }
    }

    private fun download(context: Context, repo: String, version: String) {
        val tag = "v$version"
        val name = "ByeByeRKN-fa-$tag.apk"
        val base = "https://github.com/$repo/releases/download/$tag/$name"
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, name)
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(base).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            }
            val expected = runCatching {
                client.newCall(Request.Builder().url("$base.sha256").build()).execute().use { r ->
                    r.body?.string()?.trim()?.substringBefore(' ')?.lowercase()?.takeIf { r.isSuccessful && it.length == 64 }
                }
            }.getOrNull()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            // Without the checksum file the system's signature check is the guard; a wrong checksum is refused.
            check(expected == null || expected == actual) { "checksum differs" }
        }.onSuccess {
            _state.value = State.Ready(file)
            install(context, file)
        }.onFailure {
            Log.w(TAG, "update download failed: ${it.message}")
            file.delete()
            _state.value = State.Failed
        }
    }

    private fun install(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // The user allows this app to install once, on a system screen; tapping the chip again installs.
            Toast.makeText(context, context.getString(R.string.update_allow_install), Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { _state.value = State.Failed }
    }

    private const val TAG = "Dostup/Update"
}
