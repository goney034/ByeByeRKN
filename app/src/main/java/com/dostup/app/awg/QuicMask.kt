package com.dostup.app.awg

import com.dostup.app.probe.EndpointCatalog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a genuine QUIC v1 Initial packet (RFC 9000 / RFC 9001) carrying a TLS ClientHello
 * for [build]'s SNI. AmneziaWG sends it as I1 before the WireGuard handshake, so a DPI box
 * that decrypts Initial packets sees an ordinary HTTP/3 connection to that site.
 */
object QuicMask {
    private val INITIAL_SALT_V1 = "38762cf7f55934b34d179ae6a4c80cadccbb7f0a".hexToBytes()
    private const val MIN_DATAGRAM = 1200
    private const val PN_LENGTH = 4
    private const val TAG_LENGTH = 16

    /** Popular sites to show, from the endpoint catalog so the list can be updated. */
    val defaultSni: List<String> get() = EndpointCatalog.current.quicSni

    private val random = SecureRandom()

    fun randomSni(): String = defaultSni[random.nextInt(defaultSni.size)]

    class InitialKeys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    fun clientInitialKeys(dcid: ByteArray): InitialKeys {
        val initialSecret = hmacSha256(INITIAL_SALT_V1, dcid)
        val clientSecret = expandLabel(initialSecret, "client in", 32)
        return InitialKeys(
            key = expandLabel(clientSecret, "quic key", 16),
            iv = expandLabel(clientSecret, "quic iv", 12),
            hp = expandLabel(clientSecret, "quic hp", 16),
        )
    }

    fun build(sni: String): ByteArray {
        val dcid = ByteArray(8).also(random::nextBytes)
        val keys = clientInitialKeys(dcid)
        val packetNumber = 0

        val crypto = cryptoFrame(clientHello(sni))
        val headerWithoutLength = ByteArrayOutputStream().apply {
            write(0xC0 or (PN_LENGTH - 1)) // long header, Initial, 4-byte packet number
            write(byteArrayOf(0, 0, 0, 1)) // QUIC v1
            write(dcid.size); write(dcid)
            write(0) // empty source connection id
            write(0) // no token
        }.toByteArray()

        val payloadLength = MIN_DATAGRAM - headerWithoutLength.size - 2 - PN_LENGTH - TAG_LENGTH
        check(payloadLength >= crypto.size) { "ClientHello does not fit into one datagram" }
        val payload = crypto.copyOf(payloadLength) // the rest is PADDING frames (zeros)

        val header = headerWithoutLength +
            varint(PN_LENGTH + payload.size + TAG_LENGTH, forceLength = 2) +
            ByteBuffer.allocate(PN_LENGTH).putInt(packetNumber).array()

        val nonce = keys.iv.copyOf()
        for (i in 0 until PN_LENGTH) {
            nonce[nonce.size - PN_LENGTH + i] = (nonce[nonce.size - PN_LENGTH + i].toInt() xor header[header.size - PN_LENGTH + i].toInt()).toByte()
        }
        val aead = Cipher.getInstance("AES/GCM/NoPadding")
        aead.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, nonce))
        aead.updateAAD(header)
        val ciphertext = aead.doFinal(payload)

        // Header protection: the sample starts 4 bytes after the packet number field.
        val sample = ciphertext.copyOfRange(4 - PN_LENGTH, 4 - PN_LENGTH + 16)
        val mask = headerProtectionMask(keys.hp, sample)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        val pnOffset = header.size - PN_LENGTH
        for (i in 0 until PN_LENGTH) header[pnOffset + i] = (header[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()

        return header + ciphertext
    }

    fun headerProtectionMask(hp: ByteArray, sample: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
        return ecb.doFinal(sample)
    }

    fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = "tls13 $label".toByteArray()
        val info = ByteBuffer.allocate(4 + fullLabel.size)
            .putShort(length.toShort())
            .put(fullLabel.size.toByte())
            .put(fullLabel)
            .put(0) // empty context
            .array()
        return hmacSha256(secret, info + byteArrayOf(1)).copyOf(length) // one HKDF block is enough
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun cryptoFrame(data: ByteArray): ByteArray =
        byteArrayOf(0x06) + varint(0) + varint(data.size) + data

    private fun clientHello(sni: String): ByteArray {
        val extensions = ByteArrayOutputStream()
        fun ext(type: Int, body: ByteArray) {
            extensions.write(u16(type)); extensions.write(u16(body.size)); extensions.write(body)
        }
        val name = sni.toByteArray()
        ext(0x0000, u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name) // server_name
        ext(0x000a, u16(6) + u16(0x001d) + u16(0x0017) + u16(0x0018)) // supported_groups
        val sigAlgs = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)
        ext(0x000d, u16(sigAlgs.size * 2) + sigAlgs.fold(ByteArray(0)) { acc, a -> acc + u16(a) })
        ext(0x0010, u16(3) + byteArrayOf(2) + "h3".toByteArray()) // ALPN
        ext(0x002b, byteArrayOf(2) + u16(0x0304)) // supported_versions: TLS 1.3
        ext(0x002d, byteArrayOf(1, 1)) // psk_key_exchange_modes
        val share = ByteArray(32).also(random::nextBytes)
        ext(0x0033, u16(36) + u16(0x001d) + u16(32) + share) // key_share x25519
        ext(0x0039, transportParameters())

        val body = ByteArrayOutputStream().apply {
            write(u16(0x0303))
            write(ByteArray(32).also(random::nextBytes))
            write(0) // empty legacy_session_id, as QUIC requires
            write(u16(6)); write(u16(0x1301)); write(u16(0x1302)); write(u16(0x1303))
            write(1); write(0) // null compression
            write(u16(extensions.size())); write(extensions.toByteArray())
        }.toByteArray()
        return byteArrayOf(1) + byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private fun transportParameters(): ByteArray {
        fun param(id: Int, value: Long) = varint(id) + varint(varint(value).size) + varint(value)
        return param(0x01, 30_000) + // max_idle_timeout
            param(0x03, 1472) + // max_udp_payload_size
            param(0x04, 15_728_640) + // initial_max_data
            param(0x05, 6_291_456) + param(0x06, 6_291_456) + param(0x07, 6_291_456) +
            param(0x08, 100) + param(0x09, 103) +
            varint(0x0f) + varint(0) // initial_source_connection_id (empty)
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun varint(v: Int, forceLength: Int = 0) = varint(v.toLong(), forceLength)

    private fun varint(v: Long, forceLength: Int = 0): ByteArray {
        val length = when {
            forceLength != 0 -> forceLength
            v < 0x40 -> 1
            v < 0x4000 -> 2
            v < 0x40000000 -> 4
            else -> 8
        }
        val prefix = when (length) { 1 -> 0x00; 2 -> 0x40; 4 -> 0x80; else -> 0xC0 }
        val out = ByteArray(length) { (v ushr (8 * (length - 1 - it))).toByte() }
        out[0] = (out[0].toInt() or prefix).toByte()
        return out
    }
}
