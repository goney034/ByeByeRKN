package com.dostup.app.awg

import androidx.annotation.StringRes
import com.dostup.app.R
import java.security.SecureRandom

/** What AmneziaWG sends as I1, the first datagram of every handshake. */
enum class FirstPacket { NONE, QUIC, DNS, STUN, SIP }

/**
 * AmneziaWG obfuscation presets that work against a stock WireGuard server such as WARP:
 * only client-side junk (Jc/Jmin/Jmax and I1) is allowed, S1/S2 stay 0 and H1..H4 keep the
 * standard message types, otherwise Cloudflare would not understand the handshake.
 *
 * Junk sizes are ranges: every generated config gets its own numbers, so there is no fixed
 * pattern for DPI to learn.
 */
enum class Obfuscation(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val junkCount: IntRange,
    val junkMin: IntRange,
    val junkSpread: IntRange,
    val firstPacket: FirstPacket,
) {
    LIGHT(R.string.obf_light, R.string.obf_light_summary, 3..6, 40..64, 20..60, FirstPacket.NONE),
    QUIC(R.string.obf_quic, R.string.obf_quic_summary, 3..6, 40..64, 20..60, FirstPacket.QUIC),
    STUN(R.string.obf_stun, R.string.obf_stun_summary, 3..6, 40..64, 20..60, FirstPacket.STUN),
    DNS(R.string.obf_dns, R.string.obf_dns_summary, 3..6, 40..64, 20..60, FirstPacket.DNS),
    SIP(R.string.obf_sip, R.string.obf_sip_summary, 3..6, 40..64, 20..60, FirstPacket.SIP),
    HEAVY(R.string.obf_heavy, R.string.obf_heavy_summary, 60..120, 20..40, 300..800, FirstPacket.NONE),
    NONE(R.string.obf_none, R.string.obf_none_summary, 0..0, 0..0, 0..0, FirstPacket.NONE);

    fun params(sni: String? = null): AwgParams {
        val jc = junkCount.pick()
        val jmin = if (jc == 0) 0 else junkMin.pick()
        val jmax = if (jc == 0) 0 else (jmin + junkSpread.pick()).coerceAtMost(MAX_JUNK)
        val i1 = when (firstPacket) {
            FirstPacket.NONE -> null
            FirstPacket.QUIC -> QuicMask.build(sni?.takeIf { it.isNotBlank() } ?: QuicMask.randomSni())
            FirstPacket.DNS -> Masks.dnsQuery()
            FirstPacket.STUN -> Masks.stunBindingRequest()
            FirstPacket.SIP -> Masks.sipOptions().toByteArray()
        }
        return AwgParams(this, jc, jmin, jmax, i1)
    }

    companion object {
        /** AmneziaWG rejects junk packets larger than this. */
        const val MAX_JUNK = 1280

        /** Order tried by the automatic mode: the best disguises first, then ever more desperate ones. */
        val autoOrder = listOf(QUIC, STUN, LIGHT, DNS, SIP, HEAVY, NONE)

        private val random = SecureRandom()

        private fun IntRange.pick(): Int = if (first == last) first else first + random.nextInt(last - first + 1)
    }
}

/** A concrete obfuscation setup: the preset, its randomized junk sizes and the generated I1. */
class AwgParams(
    val obfuscation: Obfuscation,
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val i1: ByteArray?,
) {
    fun configLines(): List<String> = buildList {
        if (jc > 0) {
            add("Jc = $jc")
            add("Jmin = $jmin")
            add("Jmax = $jmax")
        }
        if (jc > 0 || i1 != null) {
            add("S1 = 0")
            add("S2 = 0")
            add("H1 = 1")
            add("H2 = 2")
            add("H3 = 3")
            add("H4 = 4")
        }
        i1?.let { add("I1 = <b 0x${it.toHex()}>") }
    }

    fun i1Hex(): String? = i1?.toHex()
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(digits[(b.toInt() shr 4) and 0xf]).append(digits[b.toInt() and 0xf])
    }
    return sb.toString()
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
