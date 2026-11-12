package com.dostup.app.probe

import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import javax.net.SocketFactory

/**
 * Speaks just enough MTProto to see whether a Telegram data centre really answers: an open TCP
 * connection proves little, so this sends the first message of the key exchange (req_pq_multi)
 * and waits for res_pq.
 */
object TelegramProbe {
    class Dc(val id: Int, val v4: String, val v6: String)

    val dcs = listOf(
        Dc(1, "149.154.175.53", "2001:b28:f23d:f001::a"),
        Dc(2, "149.154.167.51", "2001:67c:4e8:f002::a"),
        Dc(3, "149.154.175.100", "2001:b28:f23d:f003::a"),
        Dc(4, "149.154.167.91", "2001:67c:4e8:f004::a"),
        Dc(5, "91.108.56.130", "2001:b28:f23f:f005::a"),
    )

    private const val PORT = 443
    private const val REQ_PQ_MULTI = 0xBE7E8EF1.toInt()
    private const val RES_PQ = 0x05162463
    private const val ABRIDGED_MARKER = 0xEF
    private val random = SecureRandom()

    /** [replyMs] is set only when Telegram answered with res_pq. */
    class Result(val connectMs: Long?, val replyMs: Long?, val error: String?) {
        val ok get() = replyMs != null
    }

    /** Blocking socket calls can't be cancelled, so a check that lost the race finishes here on its own. */
    private val executor = Executors.newCachedThreadPool { Thread(it, "telegram-probe").apply { isDaemon = true } }

    /**
     * True when Telegram answers over IPv4, which is what the app mostly uses. IPv6 can work while
     * IPv4 is cut, and that still leaves Telegram nearly dead. Two data centres, in case one is
     * down, asked at once: the first answer decides, so a dead one costs no extra timeout.
     */
    fun answers(factory: SocketFactory, timeoutMs: Int = 2_500): Boolean {
        val targets = listOf(dcs[1], dcs[3])
        val results = ExecutorCompletionService<Boolean>(executor)
        targets.forEach { dc -> results.submit { check(dc.v4, factory, timeoutMs).ok } }
        repeat(targets.size) { if (results.take().get()) return true }
        return false
    }

    fun check(address: String, factory: SocketFactory = SocketFactory.getDefault(), timeoutMs: Int = 5_000): Result {
        var connectMs: Long? = null
        return try {
            factory.createSocket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, PORT), timeoutMs)
                connectMs = (System.nanoTime() - start) / 1_000_000
                socket.soTimeout = timeoutMs
                val sent = System.nanoTime()
                socket.getOutputStream().apply {
                    write(reqPqPacket(System.currentTimeMillis()))
                    flush()
                }
                val payload = readAbridged(socket.getInputStream())
                if (isResPq(payload)) {
                    Result(connectMs, (System.nanoTime() - sent) / 1_000_000, null)
                } else {
                    Result(connectMs, null, "unexpected reply, ${payload.size} bytes")
                }
            }
        } catch (e: Exception) {
            Result(connectMs, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /** Abridged transport marker, then one packet: an unencrypted req_pq_multi with a fresh nonce. */
    internal fun reqPqPacket(nowMs: Long): ByteArray {
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        body.putLong(0) // auth_key_id: 0 means unencrypted
        body.putLong(((nowMs / 1000) shl 32) or ((nowMs % 1000) * 4_000_000L and 0xFFFFFFFCL)) // message_id, divisible by 4
        body.putInt(20) // message length: constructor + nonce
        body.putInt(REQ_PQ_MULTI)
        body.put(ByteArray(16).also(random::nextBytes))
        return byteArrayOf(ABRIDGED_MARKER.toByte(), (body.capacity() / 4).toByte()) + body.array()
    }

    internal fun isResPq(payload: ByteArray): Boolean {
        if (payload.size < 24) return false
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(20) == RES_PQ
    }

    private fun readAbridged(input: InputStream): ByteArray {
        var words = input.readOrThrow()
        if (words == 0x7F) words = input.readOrThrow() or (input.readOrThrow() shl 8) or (input.readOrThrow() shl 16)
        val payload = ByteArray(words * 4)
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n < 0) error("connection closed")
            read += n
        }
        return payload
    }

    private fun InputStream.readOrThrow(): Int = read().also { if (it < 0) error("connection closed") }
}
