package com.dostup.app.probe

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

class EndpointCatalogTest {
    @After
    fun restore() {
        EndpointCatalog.current = EndpointCatalog.BUILT_IN
    }

    @Test
    fun builtInSurvivesJson() {
        assertEquals(EndpointCatalog.BUILT_IN, EndpointCatalog.fromJson(EndpointCatalog.BUILT_IN.toJson()))
    }

    /** The file in the repository is what installed apps download: it must verify with the app's key. */
    @Test
    fun repositoryCatalogIsSignedAndValid() {
        val dir = listOf(File("../catalog"), File("catalog")).first { it.isDirectory }
        val json = File(dir, "warp.json").readBytes()
        val signature = File(dir, "warp.json.sig").readText()
        assertTrue("catalog/warp.json.sig does not match; run catalog/sign.sh", EndpointCatalog.verify(json, signature))
        val catalog = EndpointCatalog.fromJson(String(json))
        assertNotNull(catalog)
        // The file and the built-in list are released together; a newer file may differ, an equal version must not.
        if (catalog!!.version == EndpointCatalog.BUILT_IN.version) assertEquals(EndpointCatalog.BUILT_IN, catalog)
        assertTrue(catalog.version >= EndpointCatalog.BUILT_IN.version)
    }

    @Test
    fun signatureCoversEveryByte() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = EndpointCatalog.BUILT_IN.toJson().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(data)
            Base64.getEncoder().encodeToString(sign())
        }
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertTrue(EndpointCatalog.verify(data, signature, publicKey))
        val tampered = data.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(EndpointCatalog.verify(tampered, signature, publicKey))
        // Signed with another key than the app trusts.
        assertFalse(EndpointCatalog.verify(data, signature))
    }

    @Test
    fun malformedCatalogsAreRejectedWhole() {
        fun with(change: JSONObject.() -> Unit) = JSONObject(EndpointCatalog.BUILT_IN.toJson()).apply(change).toString()
        assertNull(EndpointCatalog.fromJson("not json"))
        assertNull(EndpointCatalog.fromJson(with { put("ports", listOf(443, 70_000)) }))
        assertNull(EndpointCatalog.fromJson(with { put("prefixes", listOf("162.159.192")) }))
        assertNull(EndpointCatalog.fromJson(with { put("prefixes", listOf<String>()) }))
        assertNull(EndpointCatalog.fromJson(with { put("quicSni", listOf("not a host")) }))
        assertNull(EndpointCatalog.fromJson(with { put("wellKnown", listOf("162.159.192.1")) }))
        assertNull(EndpointCatalog.fromJson(with { put("version", 0) }))
    }

    @Test
    fun laterFieldsAreIgnoredAndOptionalOnesMayBeMissing() {
        val json = JSONObject(EndpointCatalog.BUILT_IN.toJson()).put("masque", "later").apply {
            remove("v6Prefixes")
            remove("wellKnown")
        }
        val catalog = EndpointCatalog.fromJson(json.toString())
        assertNotNull(catalog)
        assertTrue(catalog!!.v6Prefixes.isEmpty())
    }

    @Test
    fun aTinyCatalogStillGivesASample() {
        EndpointCatalog.current = EndpointCatalog(99, listOf(2408), listOf("162.159.192."), emptyList(), emptyList(), listOf("ya.ru"))
        val sample = WarpEndpoints.sample(300, Random(1))
        assertEquals(254, sample.size) // every distinct endpoint there is, and no endless loop
        assertTrue(sample.all { it.startsWith("162.159.192.") })
    }

    @Test
    fun samplesTheWholeFamilyNotJustItsFirstTenHosts() {
        val hosts = WarpEndpoints.sample(200, Random(7))
            .filterNot { it.startsWith("[") }
            .map { it.substringBefore(':').substringAfterLast('.').toInt() }
        assertTrue(hosts.all { it in 1..254 })
        assertTrue("only low hosts: ${hosts.max()}", hosts.max() > 200)
    }

    @Test
    fun namesAreLeftOutOfScansAndTheZeroTrustRangeIsGone() {
        assertFalse(EndpointCatalog.BUILT_IN.prefixes.contains("162.159.193."))
        // A catalog made for older versions may still carry a name: it must not reach a scan.
        EndpointCatalog.current = EndpointCatalog.BUILT_IN.copy(version = 99, wellKnown = listOf("engage.cloudflareclient.com:2408", "162.159.192.1:2408"))
        val sample = WarpEndpoints.sample(32)
        assertTrue(sample.none { it.startsWith("engage") })
        assertTrue("162.159.192.1:2408" in sample)
    }

    @Test
    fun tellsAddressesFromNames() {
        assertTrue(WarpEndpoints.isLiteral("162.159.192.1"))
        assertTrue(WarpEndpoints.isLiteral(WarpEndpoints.hostOf("[2606:4700:d0::a29f:c001]:2408")))
        assertFalse(WarpEndpoints.isLiteral("engage.cloudflareclient.com"))
        assertFalse(WarpEndpoints.isLiteral(""))
        assertEquals(2408, WarpEndpoints.portOf("[2606:4700:d0::a29f:c001]:2408"))
    }
}
