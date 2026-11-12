package com.dostup.app.masque

/** The ways to reach WARP over MASQUE, in the order they are tried. */
enum class MasqueRoute(val title: String, val http2: Boolean, val disguisedSni: Boolean) {
    /** QUIC and HTTP/3 like the official client, with an ordinary site's name in the TLS handshake. */
    HTTP3("HTTP/3", http2 = false, disguisedSni = true),
    /** TCP and HTTP/2, for networks that drop UDP to unknown addresses. */
    HTTP2("HTTP/2", http2 = true, disguisedSni = true),
    /** As the official client shows itself, in case Cloudflare stops accepting other names. */
    HTTP3_PLAIN("HTTP/3", http2 = false, disguisedSni = false),
}
