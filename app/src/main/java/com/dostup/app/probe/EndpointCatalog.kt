package com.dostup.app.probe

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The WARP addresses, ports and disguise sites the app tries. A copy is built in; a newer one,
 * signed with the project's key, comes from the repository (catalog/warp.json), so new addresses
 * reach users without a new release. Unknown fields are ignored, so later versions can add some.
 */
data class EndpointCatalog(
    val version: Int,
    val ports: List<Int>,
    /** IPv4 families such as "162.159.192.", completed with 1..10. */
    val prefixes: List<String>,
    /** IPv6 families: the IPv4 endpoint embedded in the last bits, completed with two hex digits. */
    val v6Prefixes: List<String>,
    /** Always in the scan, before random picks. */
    val wellKnown: List<String>,
    /** Popular sites whose names the QUIC disguise and MASQUE show. */
    val quicSni: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("ports", JSONArray(ports))
        .put("prefixes", JSONArray(prefixes))
        .put("v6Prefixes", JSONArray(v6Prefixes))
        .put("wellKnown", JSONArray(wellKnown))
        .put("quicSni", JSONArray(quicSni))
        .toString(2)

    companion object {
        val BUILT_IN = EndpointCatalog(
            version = 1,
            ports = listOf(
                500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946,
                955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2408,
                2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281,
                7559, 8319, 8742, 8854, 8886,
            ),
            prefixes = listOf(
                "162.159.192.", "162.159.193.", "162.159.195.",
                "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.",
                "8.6.112.", "8.34.70.", "8.34.146.", "8.35.211.", "8.39.125.", "8.39.204.", "8.39.214.", "8.47.69.",
            ),
            v6Prefixes = listOf("2606:4700:d0::a29f:c0", "2606:4700:d1::a29f:c0", "2606:4700:d0::a29f:c3", "2606:4700:d1::a29f:c3"),
            wellKnown = listOf(
                "162.159.192.1:2408", "162.159.195.1:500", "188.114.97.1:4500", "162.159.193.10:1701",
                "engage.cloudflareclient.com:2408", "[2606:4700:d0::a29f:c001]:2408",
            ),
            quicSni = listOf(
                "yandex.ru", "ya.ru", "vk.com", "ozon.ru", "mail.ru", "rutube.ru",
                "wildberries.ru", "avito.ru", "gosuslugi.ru", "dzen.ru",
            ),
        )

        /** What searches use: the built-in list until a newer signed one arrives. */
        @Volatile
        var current: EndpointCatalog = BUILT_IN

        /** The project's catalog key (P-256). The private half never leaves the maintainer's machine. */
        const val PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERscadfs5qqBgSpgJNZF550sHGZapwRt3OtY4gbio/jbrKRZ+Jiza6k+nW2IfAC+x/Y018ms78OW0TMlQSoigvw=="

        private const val MAX_ITEMS = 1_000
        private val PREFIX = Regex("""^(\d{1,3}\.){3}$""")
        private val V6_PREFIX = Regex("""^[0-9a-fA-F:]{6,37}$""")
        private val ENDPOINT = Regex("""^(\[[0-9a-fA-F:]+]|[A-Za-z0-9.\-]+):\d{1,5}$""")
        private val HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9\-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9\-]*[A-Za-z0-9])?)+$""")

        /** True when [signature] (base64 DER ECDSA) signs [data] with [publicKey] (base64 X.509). */
        fun verify(data: ByteArray, signature: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(Base64.getMimeDecoder().decode(signature.trim()))
            }
        }.getOrDefault(false)

        /** Parses and checks a catalog; anything malformed or empty gives null, never a partial list. */
        fun fromJson(text: String): EndpointCatalog? = runCatching {
            val root = JSONObject(text)
            EndpointCatalog(
                version = root.getInt("version"),
                ports = root.getJSONArray("ports").ints(),
                prefixes = root.getJSONArray("prefixes").strings(),
                v6Prefixes = root.optJSONArray("v6Prefixes")?.strings().orEmpty(),
                wellKnown = root.optJSONArray("wellKnown")?.strings().orEmpty(),
                quicSni = root.getJSONArray("quicSni").strings(),
            )
        }.getOrNull()?.takeIf { it.isSane() }

        private fun EndpointCatalog.isSane(): Boolean =
            version >= 1 &&
                ports.isNotEmpty() && ports.size <= MAX_ITEMS && ports.all { it in 1..65_535 } &&
                prefixes.isNotEmpty() && prefixes.size <= MAX_ITEMS && prefixes.all { PREFIX.matches(it) } &&
                v6Prefixes.size <= MAX_ITEMS && v6Prefixes.all { V6_PREFIX.matches(it) } &&
                wellKnown.size <= MAX_ITEMS && wellKnown.all { ENDPOINT.matches(it) } &&
                quicSni.isNotEmpty() && quicSni.size <= MAX_ITEMS && quicSni.all { HOST.matches(it) }

        private fun JSONArray.strings() = List(length()) { getString(it) }
        private fun JSONArray.ints() = List(length()) { getInt(it) }
    }
}
