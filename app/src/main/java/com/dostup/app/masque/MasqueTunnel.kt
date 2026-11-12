package com.dostup.app.masque

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.dostup.app.warp.MasqueAccount
import kotlinx.coroutines.delay
import org.amnezia.awg.backend.GoBackend
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Runs the MASQUE core, libmasque.so built from usque (github.com/Diniboy1123/usque, MIT), as a
 * child process and serves its two requests over a Unix socket in the app's private files: the
 * VPN interface, and excluding each of its sockets from the VPN (VpnService.protect), without
 * which the tunnel would route into itself. The core reports on stdout, one JSON object a line.
 */
class MasqueTunnel(private val context: Context) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, BINARY)
    private val socketFile = File(context.noBackupFilesDir, "masque.sock")

    private var process: Process? = null
    private var server: LocalServerSocket? = null
    private var tun: ParcelFileDescriptor? = null

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var rx = 0L
        private set
    @Volatile var tx = 0L
        private set

    /** Not built for every ABI: x86 emulators go without. */
    val available: Boolean get() = binary.exists()

    /** [endpoint] is host:port, the address the registration gave unless the caller knows better. */
    @Synchronized
    fun start(
        tun: ParcelFileDescriptor,
        account: MasqueAccount,
        route: MasqueRoute,
        sni: String,
        mtu: Int,
        endpoint: String = "${account.endpointV4}:$PORT",
    ) {
        stop()
        connected = false
        lastError = null
        rx = 0
        tx = 0
        this.tun = tun

        socketFile.delete()
        val listening = LocalSocket(LocalSocket.SOCKET_STREAM)
        listening.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(listening.fileDescriptor).also { this.server = it }

        val process = ProcessBuilder(binary.absolutePath, socketFile.absolutePath).start().also { this.process = it }
        process.outputStream.use { it.write(coreConfig(account, route, sni, mtu, endpoint).toString().toByteArray()) }

        thread(name = "masque-control", isDaemon = true) { serve(server, tun.fileDescriptor) }
        thread(name = "masque-events", isDaemon = true) { readEvents(process) }
        thread(name = "masque-log", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine {
                    // Link-local IPv6 chatter (hop limit 1) can't cross the tunnel; one line per packet is noise.
                    if (HOP_LIMIT_NOISE !in it) Log.d(TAG, it)
                }
            }
        }
    }

    /** True once the core reports a working tunnel; false if it fails or the time is up. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connected) return true
            if (process?.isAlive != true) return false
            delay(POLL_MS)
        }
        return connected
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process?.waitForExitQuietly()
        process = null
        runCatching { server?.close() }
        server = null
        runCatching { tun?.close() }
        tun = null
        socketFile.delete()
        connected = false
    }

    private fun coreConfig(account: MasqueAccount, route: MasqueRoute, sni: String, mtu: Int, endpoint: String) = JSONObject()
        .put("private_key", account.privateKey)
        .put("endpoint_pub_key", account.endpointPublicKey)
        .put("endpoint", endpoint)
        .put("http2", route.http2)
        .put("sni", if (route.disguisedSni) sni else OFFICIAL_SNI)
        .put("mtu", mtu)

    private fun serve(server: LocalServerSocket, tunFd: FileDescriptor) {
        try {
            server.accept().use { socket ->
                val input = socket.inputStream
                val output = socket.outputStream
                while (true) {
                    when (input.read()) {
                        -1 -> return
                        REQUEST_TUN -> {
                            socket.setFileDescriptorsForSend(arrayOf(tunFd))
                            output.write(1)
                            socket.setFileDescriptorsForSend(null)
                        }
                        REQUEST_PROTECT -> {
                            val received = socket.ancillaryFileDescriptors?.firstOrNull()
                            val ok = received != null && runCatching {
                                ParcelFileDescriptor.dup(received).use { GoBackend.protectFd(it.fd) }
                            }.getOrDefault(false)
                            received?.let { runCatching { Os.close(it) } }
                            output.write(if (ok) 1 else 0)
                        }
                        else -> return
                    }
                }
            }
        } catch (e: IOException) {
            // Closed by stop(), or the core exited.
            Log.d(TAG, "control connection ended: ${e.message}")
        }
    }

    private fun readEvents(process: Process) {
        runCatching {
            process.inputStream.bufferedReader().forEachLine { line ->
                val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when (event.optString("event")) {
                    "connected" -> {
                        connected = true
                        Log.i(TAG, "connected")
                    }
                    "disconnected" -> {
                        connected = false
                        lastError = event.optString("error")
                        Log.i(TAG, "disconnected: $lastError")
                    }
                    "stats" -> {
                        rx = event.optLong("rx")
                        tx = event.optLong("tx")
                    }
                    "fatal" -> {
                        connected = false
                        lastError = event.optString("error")
                        Log.w(TAG, "core stopped: $lastError")
                    }
                }
            }
        }
        if (this.process === process) connected = false
    }

    private fun Process.waitForExitQuietly() {
        runCatching {
            val deadline = System.currentTimeMillis() + STOP_WAIT_MS
            while (isAlive && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            if (isAlive) destroyForcibly()
        }
    }

    private companion object {
        const val TAG = "Dostup/Masque"
        /** Packaged as a library so Android installs it executable next to libwg-go.so. */
        const val BINARY = "libmasque.so"
        const val PORT = 443
        const val OFFICIAL_SNI = "consumer-masque.cloudflareclient.com"
        const val HOP_LIMIT_NOISE = "Hop Limit too small"
        const val REQUEST_TUN = 'T'.code
        const val REQUEST_PROTECT = 'P'.code
        const val POLL_MS = 100L
        const val STOP_WAIT_MS = 1_000L
    }
}
