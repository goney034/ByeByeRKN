package com.dostup.app.vpn

/**
 * Watches the tunnel's byte counters and handshake time and says when a session looks dead:
 * either the handshake went stale (WireGuard drops sessions after 180 s), or apps keep sending
 * while nothing comes back, which is how DPI that cuts a flow mid-way shows up.
 *
 * Keepalives alone (32 bytes every 25 s) stay far below [minTxBytes], so an idle phone is fine.
 */
class StallDetector(
    private val windowMs: Long = 20_000,
    private val minTxBytes: Long = 4_096,
    private val maxHandshakeAgeSec: Long = 200,
) {
    private class Sample(val atMs: Long, val rx: Long, val tx: Long)

    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    /** Why a session should be checked. */
    sealed interface Suspicion {
        data class StaleHandshake(val ageSec: Long) : Suspicion
        data object NoReplies : Suspicion
    }

    /** Returns why the session should be checked, or null while it looks healthy. */
    fun update(nowMs: Long, rx: Long, tx: Long, lastHandshakeSec: Long): Suspicion? {
        if (lastHandshakeSec > 0) {
            val age = nowMs / 1000 - lastHandshakeSec
            if (age > maxHandshakeAgeSec) return Suspicion.StaleHandshake(age)
        }
        // Counters restart when the tunnel is replaced; old samples are meaningless then.
        if (samples.isNotEmpty() && (rx < samples.last().rx || tx < samples.last().tx)) samples.clear()
        samples.addLast(Sample(nowMs, rx, tx))
        while (samples.size > 1 && nowMs - samples.first().atMs > windowMs) samples.removeFirst()

        val oldest = samples.first()
        if (nowMs - oldest.atMs < windowMs * 3 / 4) return null // not enough history yet
        val sent = tx - oldest.tx
        val received = rx - oldest.rx
        return if (sent >= minTxBytes && received == 0L) Suspicion.NoReplies else null
    }
}
