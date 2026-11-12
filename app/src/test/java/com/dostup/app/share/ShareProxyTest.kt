package com.dostup.app.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ShareProxyTest {
    private lateinit var target: ServerSocket
    private lateinit var proxy: ShareProxy
    private var proxyPort = 0
    private var allowed = true

    /** A stand-in site: answers every connection with what it received, uppercased, until EOF. */
    @Before
    fun setUp() {
        target = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!target.isClosed) {
                val socket = runCatching { target.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val data = it.getInputStream().readBytes()
                        it.getOutputStream().write(String(data).uppercase().toByteArray())
                    }
                }
            }
        }
        proxyPort = ServerSocket(0).use { it.localPort }
        proxy = ShareProxy(proxyPort, acceptOn = { allowed })
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        target.close()
    }

    private fun client() = Socket().apply {
        soTimeout = 5_000
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxyPort), 5_000)
    }

    @Test
    fun socks5ConnectReachesTheSite() {
        client().use { socket ->
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            assertEquals(listOf<Byte>(5, 0), ByteArray(2).also(input::readFully).toList())
            val host = "localhost".toByteArray()
            val port = target.localPort
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((port shr 8).toByte(), port.toByte()))
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(0, reply[1].toInt())
            out.write("hello".toByteArray())
            socket.shutdownOutput()
            assertEquals("HELLO", String(input.readBytes()))
        }
    }

    @Test
    fun socks5UdpIsRefused() {
        client().use { socket ->
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            ByteArray(2).also(input::readFully)
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(7, reply[1].toInt())
        }
    }

    @Test
    fun httpConnectTunnels() {
        client().use { socket ->
            val out = socket.getOutputStream()
            out.write("CONNECT 127.0.0.1:${target.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            val head = readHead(input)
            assertTrue(head, head.startsWith("HTTP/1.1 200"))
            out.write("secret".toByteArray())
            socket.shutdownOutput()
            assertEquals("SECRET", String(input.readBytes()))
        }
    }

    @Test
    fun plainHttpIsRewrittenToOriginForm() {
        client().use { socket ->
            val out = socket.getOutputStream()
            out.write(
                ("GET http://127.0.0.1:${target.localPort}/path?q=1 HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Proxy-Connection: keep-alive\r\n\r\n").toByteArray(),
            )
            socket.shutdownOutput()
            val echoed = String(socket.getInputStream().readBytes())
            assertTrue(echoed, echoed.startsWith("GET /PATH?Q=1 HTTP/1.1\r\n"))
            assertTrue(echoed, "PROXY-CONNECTION" !in echoed)
            assertTrue(echoed, "CONNECTION: CLOSE" in echoed)
        }
    }

    @Test
    fun connectionsFromOtherInterfacesAreDropped() {
        allowed = false
        client().use { socket ->
            runCatching { socket.getOutputStream().write(byteArrayOf(5, 1, 0)) }
            // Closed without a word: an end of stream, or a reset where the system reports it so (Windows).
            val read = runCatching { socket.getInputStream().read() }
            assertTrue("got ${read.getOrNull()}", read.isFailure || read.getOrNull() == -1)
        }
    }

    @Test
    fun parsesConnectTargets() {
        assertEquals("example.com" to 443, ShareProxy.hostAndPort("example.com:443"))
        assertEquals("2001:db8::1" to 8443, ShareProxy.hostAndPort("[2001:db8::1]:8443"))
        assertEquals(null, ShareProxy.hostAndPort("example.com"))
        assertEquals(null, ShareProxy.hostAndPort("example.com:99999"))
    }

    private fun readHead(input: java.io.InputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            text.append(b.toChar())
        }
        return text.toString()
    }
}
