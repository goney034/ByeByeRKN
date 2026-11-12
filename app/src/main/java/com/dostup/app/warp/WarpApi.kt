package com.dostup.app.warp

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

data class WarpAccount(
    val id: String,
    val token: String,
    val privateKey: String,
    val peerPublicKey: String,
    val addressV4: String,
    val addressV6: String,
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("token", token)
        .put("privateKey", privateKey).put("peerPublicKey", peerPublicKey)
        .put("addressV4", addressV4).put("addressV6", addressV6)
        .toString()

    companion object {
        fun fromJson(json: String): WarpAccount = JSONObject(json).run {
            WarpAccount(
                getString("id"), getString("token"), getString("privateKey"),
                getString("peerPublicKey"), getString("addressV4"), getString("addressV6"),
            )
        }
    }
}

/**
 * A WARP device enrolled for MASQUE. It is a device of its own: enrolling a MASQUE key replaces
 * a device's WireGuard key, so the AmneziaWG account can't double as this one.
 */
data class MasqueAccount(
    val id: String,
    val token: String,
    /** P-256 key, base64 of its PKCS#8 DER. */
    val privateKey: String,
    /** The server's key to pin, PEM. */
    val endpointPublicKey: String,
    val endpointV4: String,
    val endpointV6: String,
    val addressV4: String,
    val addressV6: String,
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("token", token).put("privateKey", privateKey)
        .put("endpointPublicKey", endpointPublicKey).put("endpointV4", endpointV4).put("endpointV6", endpointV6)
        .put("addressV4", addressV4).put("addressV6", addressV6)
        .toString()

    companion object {
        fun fromJson(json: String): MasqueAccount = JSONObject(json).run {
            MasqueAccount(
                getString("id"), getString("token"), getString("privateKey"), getString("endpointPublicKey"),
                getString("endpointV4"), getString("endpointV6"), getString("addressV4"), getString("addressV6"),
            )
        }

        /** "162.159.198.1:0" or "[2606:4700:103::]:0" without the port and brackets. */
        internal fun hostOf(endpoint: String): String =
            endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    }
}

/** What binding a WARP+ key gave: [warpPlus] is true once the account is WARP+. */
data class WarpPlusResult(val accountType: String, val warpPlus: Boolean)

/** [httpCode] is set when the server answered, i.e. the network path itself worked. */
class WarpApiException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    /** Cloudflare's own error text, such as "Invalid license", when it sent one. */
    val apiMessage: String? = null,
) : IOException(message, cause)

/**
 * Registers a device with Cloudflare's (undocumented) WARP client API, the same calls the
 * official client makes. The private key is generated locally and never leaves the device.
 *
 * Russian ISPs drop the TLS handshake to api.cloudflareclient.com by SNI (case-insensitive,
 * with TCP reassembly), while zero-trust-client.cloudflareclient.com serves the same API and
 * passes. Each host is tried directly first, then with a split ClientHello as a last resort.
 */
class WarpApi(private val hosts: List<String> = DEFAULT_HOSTS) {
    private val direct = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val split = direct.newBuilder()
        .socketFactory(SplitHelloSocketFactory())
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun register(onRetry: () -> Unit = {}): WarpAccount = viaAnyRoute(onRetry) { client, host ->
        register(client, "https://$host/$API_VERSION/")
    }

    /**
     * Registers a device the way the current official Android client does and enrolls a P-256
     * key for MASQUE, as usque does (github.com/Diniboy1123/usque, MIT).
     */
    fun registerMasque(onRetry: () -> Unit = {}): MasqueAccount = viaAnyRoute(onRetry) { client, host ->
        registerMasque(client, "https://$host/$MASQUE_API_VERSION/")
    }

    /** Binds a WARP+ key to the AmneziaWG device, as the official client's "change key" does. */
    fun applyLicense(account: WarpAccount, license: String): WarpPlusResult = viaAnyRoute({}) { client, host ->
        val body = JSONObject().put("license", license)
        accountResult(call(client, "https://$host/$API_VERSION/", "PUT", "reg/${account.id}/account", body, account.token).unwrapped())
    }

    /** The same for the MASQUE device: one key covers several devices. */
    fun applyLicense(account: MasqueAccount, license: String): WarpPlusResult = viaAnyRoute({}) { client, host ->
        val body = JSONObject().put("license", license)
        val reply = call(client, "https://$host/$MASQUE_API_VERSION/", "PUT", "reg/${account.id}/account", body, account.token, MASQUE_HEADERS)
        accountResult(reply.unwrapped())
    }

    private fun accountResult(json: JSONObject) = WarpPlusResult(json.optString("account_type"), json.optBoolean("warp_plus"))

    private fun <T> viaAnyRoute(onRetry: () -> Unit, block: (OkHttpClient, String) -> T): T {
        val attempts = hosts.map { it to direct } + hosts.map { it to split }
        var lastError: WarpApiException? = null
        for ((index, attempt) in attempts.withIndex()) {
            val (host, client) = attempt
            if (index > 0) onRetry()
            try {
                return block(client, host)
            } catch (e: WarpApiException) {
                // The server answered with an error: another route will not change that.
                if (e.httpCode != null) throw e
                Log.i(TAG, "registration via $host (${if (client === split) "split" else "direct"}) failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: WarpApiException("no API hosts configured")
    }

    private fun registerMasque(client: OkHttpClient, baseUrl: String): MasqueAccount {
        // The official client registers with a WireGuard key first; only its public half is ever used.
        val registration = call(
            client, baseUrl, "POST", "reg",
            JSONObject()
                .put("key", KeyPair().publicKey.toBase64())
                .put("install_id", "")
                .put("fcm_token", "")
                .put("tos", OffsetDateTime.now().format(CF_TIME))
                .put("model", "PC")
                .put("serial_number", ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) })
                .put("os_version", "")
                .put("key_type", "curve25519")
                .put("tunnel_type", "wireguard")
                .put("locale", "en_US"),
            headers = MASQUE_HEADERS,
        ).unwrapped()
        val id = registration.getString("id")
        val token = registration.getString("token")

        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val enrolled = call(
            client, baseUrl, "PATCH", "reg/$id",
            JSONObject()
                .put("key", Base64.getEncoder().encodeToString(ec.public.encoded))
                .put("key_type", "secp256r1")
                .put("tunnel_type", "masque"),
            token,
            MASQUE_HEADERS,
        ).unwrapped()
        val config = enrolled.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val endpoint = peer.getJSONObject("endpoint")
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        return MasqueAccount(
            id = id,
            token = token,
            privateKey = Base64.getEncoder().encodeToString(ec.private.encoded),
            endpointPublicKey = peer.getString("public_key"),
            endpointV4 = MasqueAccount.hostOf(endpoint.getString("v4")),
            endpointV6 = MasqueAccount.hostOf(endpoint.getString("v6")),
            addressV4 = addresses.getString("v4"),
            addressV6 = addresses.getString("v6"),
        )
    }

    /** The current API answers with the object itself; the old one wraps it in "result". */
    private fun JSONObject.unwrapped(): JSONObject = optJSONObject("result") ?: this

    private fun register(client: OkHttpClient, baseUrl: String): WarpAccount {
        val keyPair = KeyPair()
        val registration = call(
            client, baseUrl, "POST", "reg",
            JSONObject()
                .put("install_id", "")
                .put("tos", Instant.now().toString())
                .put("key", keyPair.publicKey.toBase64())
                .put("fcm_token", "")
                .put("type", "ios")
                .put("locale", "en_US"),
        ).getJSONObject("result")
        val id = registration.getString("id")
        val token = registration.getString("token")

        val config = call(client, baseUrl, "PATCH", "reg/$id", JSONObject().put("warp_enabled", true), token)
            .getJSONObject("result").getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        return WarpAccount(
            id = id,
            token = token,
            privateKey = keyPair.privateKey.toBase64(),
            peerPublicKey = peer.getString("public_key"),
            addressV4 = addresses.getString("v4"),
            addressV6 = addresses.getString("v6"),
        )
    }

    private fun call(
        client: OkHttpClient,
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject,
        token: String? = null,
        headers: Map<String, String> = mapOf("User-Agent" to "CloudflareWarp/1.0"),
    ): JSONObject {
        val request = Request.Builder()
            .url(baseUrl + path)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMessage = runCatching {
                        JSONObject(text).getJSONArray("errors").getJSONObject(0).getString("message")
                    }.getOrNull()
                    throw WarpApiException("HTTP ${response.code}: ${apiMessage ?: text.take(200)}", httpCode = response.code, apiMessage = apiMessage)
                }
                return JSONObject(text)
            }
        } catch (e: WarpApiException) {
            throw e
        } catch (e: Exception) {
            throw WarpApiException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    companion object {
        private const val TAG = "Dostup/Warp"
        private const val API_VERSION = "v0i1909051800"
        private const val MASQUE_API_VERSION = "v0a4471"
        private val MASQUE_HEADERS = mapOf("User-Agent" to "WARP for Android", "CF-Client-Version" to "a-6.35-4471")
        private val CF_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        private val random = SecureRandom()
        private val LICENSE = Regex("""^[A-Za-z0-9]{8}-[A-Za-z0-9]{8}-[A-Za-z0-9]{8}$""")
        val DEFAULT_HOSTS = listOf("zero-trust-client.cloudflareclient.com", "api.cloudflareclient.com")

        /** WARP+ keys look like xxxxxxxx-xxxxxxxx-xxxxxxxx. */
        fun isLicense(key: String): Boolean = LICENSE.matches(key)
    }
}
