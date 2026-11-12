package com.dostup.app

import com.dostup.app.warp.WarpApi
import com.dostup.app.warp.WarpApiException
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sends a made-up WARP+ key to the real API: Cloudflare must turn it down with an answer of its
 * own, which shows the request reaches the right place in the right shape.
 * Runs only with DOSTUP_LIVE=1 so regular builds stay offline.
 */
class LiveWarpPlusTest {
    @Test
    fun aMadeUpKeyIsRejectedByCloudflare() {
        assumeTrue(System.getenv("DOSTUP_LIVE") == "1")
        val api = WarpApi()
        val account = api.register()
        val error = runCatching { api.applyLicense(account, "aaaaaaaa-bbbbbbbb-cccccccc") }.exceptionOrNull()
        println("WARP+ answer: ${error?.message ?: "accepted?!"}")
        assertNotNull("a made-up key must not be accepted", error)
        assertNotNull("Cloudflare must answer, not the network fail", (error as? WarpApiException)?.httpCode)
    }
}
