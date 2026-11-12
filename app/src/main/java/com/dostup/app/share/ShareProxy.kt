package com.dostup.app.share

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Shares the VPN with other devices without root. Android sends packets of hotspot clients past
 * app VPNs, but connections this app opens itself go through the tunnel like any other app's, so
 * a proxy on the phone does the job. One port speaks HTTP (with CONNECT) and SOCKS5, told apart by
 * the first byte. TCP only: SOCKS5 UDP is refused, so games and calls on those devices go direct.
 */
class ShareProxy(
    private val port: Int = DEFAULT_PORT,
    /** Whether a connection that arrived on this local address may use the proxy. */
    private val acceptOn: (InetAddress) -> Boolean,
    /** Opens the outgoing connection; inside the app it goes through the VPN. */
    private val connect: (host: String, port: Int) -> Socket = ::defaultConnect,
) {
    private val pool = Executors.newCachedThreadPool { Thread(it, "share-proxy").apply { isDaemon = true } }
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val clients = AtomicInteger()
    @Volatile private var server: ServerSocket? = null

    val running: Boolean get() = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), BACKLOG)
        }
        server = socket
        thread(name = "share-proxy-accept", isDaemon = true) { acceptLoop(socket) }
        Log.i(TAG, "listening on port $port")
    }

    /** Also cuts open connections: without the VPN they would carry nothing, or leak around it. */
    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        runCatching { socket.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        Log.i(TAG, "stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (!acceptOn(client.localAddress) || clients.get() >= MAX_CLIENTS) {
                runCatching { client.close() }
                continue
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        clients.incrementAndGet()
        sockets += client
        var upstream: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.mark(1)
            val first = input.read()
            input.reset()
            upstream = when (first) {
                -1 -> null
                SOCKS_VERSION -> socks(input, output)
                else -> http(input, output)
            } ?: return
            sockets += upstream
            client.soTimeout = 0
            relay(client, input, upstream)
        } catch (_: IOException) {
            // The device or the site went away; nothing to report to anyone.
        } finally {
            runCatching { client.close() }
            upstream?.let { runCatching { it.close() } }
            sockets -= client
            upstream?.let { sockets -= it }
            clients.decrementAndGet()
        }
    }

    /** RFC 1928, CONNECT without authentication: only devices on the phone's own tethering get here. */
    private fun socks(input: InputStream, output: OutputStream): Socket? {
        input.read() // version, checked by the caller
        val methods = input.readExactly(input.readByte())
        if (NO_AUTH !in methods) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE_METHOD.toByte()))
            return null
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH))
        val request = input.readExactly(4)
        if (request[0].toInt() != SOCKS_VERSION) return null
        val host = when (request[3].toInt()) {
            1 -> InetAddress.getByAddress(input.readExactly(4)).hostAddress
            3 -> String(input.readExactly(input.readByte()), Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
            else -> return socksReply(output, ADDRESS_TYPE_NOT_SUPPORTED)
        } ?: return null
        val port = (input.readByte() shl 8) or input.readByte()
        if (request[1].toInt() != CMD_CONNECT) return socksReply(output, COMMAND_NOT_SUPPORTED)
        val upstream = try {
            connect(host, port)
        } catch (e: UnknownHostException) {
            return socksReply(output, HOST_UNREACHABLE)
        } catch (e: IOException) {
            return socksReply(output, CONNECTION_REFUSED)
        }
        socksReply(output, SUCCEEDED)
        return upstream
    }

    private fun socksReply(output: OutputStream, code: Int): Socket? {
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        return null
    }

    /** CONNECT for HTTPS, and plain HTTP requests in absolute form, one request per connection. */
    private fun http(input: InputStream, output: OutputStream): Socket? {
        val head = readHead(input) ?: return httpError(output, "400 Bad Request")
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return httpError(output, "400 Bad Request")
        val (method, target, version) = parts
        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = hostAndPort(target) ?: return httpError(output, "400 Bad Request")
            val upstream = runCatching { connect(host, port) }.getOrElse { return httpError(output, "502 Bad Gateway") }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            return upstream
        }
        val uri = runCatching { URI(target) }.getOrNull()
            ?.takeIf { it.scheme.equals("http", ignoreCase = true) && it.host != null }
            ?: return httpError(output, "400 Bad Request")
        val upstream = runCatching { connect(uri.host, if (uri.port == -1) 80 else uri.port) }
            .getOrElse { return httpError(output, "502 Bad Gateway") }
        val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val headers = lines.drop(1).filter {
            it.isNotEmpty() && !it.startsWith("Proxy-", ignoreCase = true) && !it.startsWith("Connection:", ignoreCase = true)
        }
        // One request per connection: a follow-up for another site must not reach this one.
        val request = buildString {
            append("$method $path $version\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        upstream.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        return upstream
    }

    private fun httpError(output: OutputStream, status: String): Socket? {
        output.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        return null
    }

    /** Copies both ways until each side is done; the request body, if any, is still in [clientInput]. */
    private fun relay(client: Socket, clientInput: InputStream, upstream: Socket) {
        val downstream = pool.submit {
            runCatching { upstream.getInputStream().copyTo(client.getOutputStream(), BUFFER) }
            runCatching { client.shutdownOutput() }
        }
        runCatching { clientInput.copyTo(upstream.getOutputStream(), BUFFER) }
        runCatching { upstream.shutdownOutput() }
        runCatching { downstream.get() }
    }

    companion object {
        private const val TAG = "Dostup/Share"
        const val DEFAULT_PORT = 8080
        private const val BACKLOG = 50
        private const val MAX_CLIENTS = 256
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 16 * 1024
        private const val BUFFER = 16 * 1024
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH: Byte = 0
        private const val NO_ACCEPTABLE_METHOD = 0xFF
        private const val CMD_CONNECT = 1
        private const val SUCCEEDED = 0
        private const val HOST_UNREACHABLE = 4
        private const val CONNECTION_REFUSED = 5
        private const val COMMAND_NOT_SUPPORTED = 7
        private const val ADDRESS_TYPE_NOT_SUPPORTED = 8

        fun defaultConnect(host: String, port: Int): Socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }

        /** "host:port" or "[v6]:port"; CONNECT targets always carry the port. */
        internal fun hostAndPort(target: String): Pair<String, Int>? {
            val separator = target.lastIndexOf(':')
            if (separator <= 0) return null
            val host = target.substring(0, separator).removePrefix("[").removeSuffix("]")
            val port = target.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            return host.takeIf { it.isNotEmpty() }?.let { it to port }
        }

        /** Request line and headers up to the blank line, or null if they never end. */
        private fun readHead(input: InputStream): String? {
            val bytes = ArrayList<Byte>(512)
            while (bytes.size < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                bytes += b.toByte()
                val n = bytes.size
                if (n >= 4 && bytes[n - 4] == CR && bytes[n - 3] == LF && bytes[n - 2] == CR && bytes[n - 1] == LF) {
                    return String(bytes.toByteArray(), 0, n - 4, Charsets.ISO_8859_1)
                }
            }
            return null
        }

        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        private fun InputStream.readByte(): Int = read().also { if (it < 0) throw EOFException() }

        private fun InputStream.readExactly(count: Int): ByteArray {
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = read(buffer, read, count - read)
                if (n < 0) throw EOFException()
                read += n
            }
            return buffer
        }
    }
}
