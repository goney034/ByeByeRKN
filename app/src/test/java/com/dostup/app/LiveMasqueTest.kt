package com.dostup.app

import com.dostup.app.warp.WarpApi
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Registers a real MASQUE device. With DOSTUP_MASQUE_CONFIG set it also writes usque's
 * config.json there, so the registration can be tried with usque itself from this machine.
 * Runs only with DOSTUP_LIVE=1 so regular builds stay offline.
 */
class LiveMasqueTest {
    @Test
    fun registerMasqueDevice() {
        assumeTrue(System.getenv("DOSTUP_LIVE") == "1")

        val account = WarpApi().registerMasque()
        println("masque device: endpoint v4=${account.endpointV4} v6=${account.endpointV6} address=${account.addressV4}")

        val path = System.getenv("DOSTUP_MASQUE_CONFIG") ?: return
        // usque reads a SEC 1 key; Java produces PKCS#8 around it, with the curve outside the key.
        val info = PrivateKeyInfo.getInstance(Base64.getDecoder().decode(account.privateKey))
        val key = ECPrivateKey.getInstance(info.parsePrivateKey())
        val sec1 = ECPrivateKey(256, key.key, key.publicKey, info.privateKeyAlgorithm.parameters).encoded
        val config = JSONObject()
            .put("private_key", Base64.getEncoder().encodeToString(sec1))
            .put("endpoint_v4", account.endpointV4)
            .put("endpoint_v6", account.endpointV6)
            .put("endpoint_h2_v4", "162.159.198.2")
            .put("endpoint_h2_v6", "")
            .put("endpoint_pub_key", account.endpointPublicKey)
            .put("id", account.id)
            .put("access_token", account.token)
            .put("ipv4", account.addressV4)
            .put("ipv6", account.addressV6)
        File(path).writeText(config.toString(2))
        println("usque config written")
    }
}
