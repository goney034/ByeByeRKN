package com.dostup.app.data

import com.dostup.app.awg.Obfuscation
import com.dostup.app.masque.MasqueRoute
import com.dostup.app.probe.WarpEndpoints
import com.dostup.app.vpn.ExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBookTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600 * 1000L

    private fun route(endpoint: String, mask: Obfuscation = Obfuscation.LIGHT) = Candidate(endpoint, mask.params(), 40)

    @Test
    fun goodRoutesComeFirst() {
        val book = RouteBook()
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        book.recordSuccess(route("162.159.192.8:891"), good = true, now - 1000)
        assertEquals("162.159.192.8:891", book.known(now).first().endpoint)
    }

    @Test
    fun goodRoutesAreRetriedWhileDegradedOnesOnlyServeAsFallback() {
        val book = RouteBook()
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        book.recordSuccess(route("8.47.69.8:1010"), good = false, now - 1000)
        book.recordSuccess(route("162.159.192.8:891"), good = true, now - 2000)
        assertEquals(listOf("162.159.192.8:891"), book.knownGood(now).map { it.endpoint })
        assertEquals("8.35.211.9:891", book.knownFallback(now)?.endpoint)
    }

    @Test
    fun repeatedFailuresForgetARoute() {
        val book = RouteBook()
        val r = route("162.159.192.8:891")
        book.recordSuccess(r, good = true, now)
        repeat(RouteBook.MAX_FAILURES - 1) { book.recordFailure(r) }
        assertEquals(1, book.known(now).size)
        book.recordFailure(r)
        assertTrue(book.known(now).isEmpty())
    }

    @Test
    fun oldRoutesExpire() {
        val book = RouteBook()
        book.recordSuccess(route("162.159.192.8:891"), good = true, now)
        assertTrue(book.known(now + RouteBook.ROUTE_TTL_MS).isEmpty())
    }

    @Test
    fun badFamilyLastAndExpiresSoRoutingChangesAreNoticed() {
        val book = RouteBook()
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        val hits = listOf(route("8.35.211.2:500"), route("162.159.192.8:891"))
        assertEquals("162.159.192.8:891", book.attemptOrder(hits, now).first().endpoint)
        assertTrue(book.isBadFamily("8.35.211.77:1", now))
        assertFalse(book.isBadFamily("8.35.211.77:1", now + RouteBook.BAD_FAMILY_TTL_MS))
        assertFalse(book.isBadFamily("8.35.211.77:1", now + 2 * day))
    }

    @Test
    fun aGoodResultClearsTheFamilysBadMark() {
        val book = RouteBook()
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        book.recordSuccess(route("8.35.211.2:500"), good = true, now)
        assertFalse(book.isBadFamily("8.35.211.9:891", now))
    }

    @Test
    fun onePerFamilyWhenChoosingAttempts() {
        val hits = listOf(route("162.159.192.8:891"), route("162.159.192.1:2408"), route("8.6.112.1:500"))
        assertEquals(2, RouteBook().attemptOrder(hits, now).size)
    }

    @Test
    fun ipv6IsTriedAfterIpv4ButBeforeKnownBadFamilies() {
        val book = RouteBook()
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        val hits = listOf(route("[2606:4700:d0::a29f:c009]:7103"), route("8.35.211.2:500"), route("162.159.192.8:891"))
        assertEquals(
            listOf("162.159.192.8:891", "[2606:4700:d0::a29f:c009]:7103", "8.35.211.2:500"),
            book.attemptOrder(hits, now).map { it.endpoint },
        )
    }

    @Test
    fun keepsOnlyTheBestRoutes() {
        val book = RouteBook()
        repeat(RouteBook.MAX_ROUTES + 3) { book.recordSuccess(route("162.159.192.${it + 1}:891"), good = true, now + it) }
        assertEquals(RouteBook.MAX_ROUTES, book.known(now + 100).size)
    }

    @Test
    fun survivesJsonRoundTrip() {
        val book = RouteBook()
        book.recordSuccess(route("162.159.192.8:891", Obfuscation.QUIC).withMtu(1200), good = true, now)
        book.recordSuccess(route("8.35.211.9:891"), good = false, now)
        val restored = RouteBook.fromJson(book.toJson())
        assertEquals(1200, restored.known(now).first().mtu)
        assertEquals(Obfuscation.QUIC, restored.known(now).first().params.obfuscation)
        assertTrue(restored.isBadFamily("8.35.211.1:1", now))
    }

    @Test
    fun masqueThatWorkedIsRememberedAndSurvivesJson() {
        val book = RouteBook()
        book.recordMasqueSuccess(MasqueRoute.HTTP2, good = true, now)
        val restored = RouteBook.fromJson(book.toJson())
        assertEquals(MasqueRoute.HTTP2, restored.knownMasque(now))
        assertEquals(null, restored.knownMasque(now + RouteBook.ROUTE_TTL_MS))
    }

    @Test
    fun degradedMasqueIsNotTriedFirst() {
        val book = RouteBook()
        book.recordMasqueSuccess(MasqueRoute.HTTP3, good = false, now)
        assertEquals(null, book.knownMasque(now))
    }

    @Test
    fun masqueIsForgottenAfterRepeatedFailures() {
        val book = RouteBook()
        book.recordMasqueSuccess(MasqueRoute.HTTP3, good = true, now)
        repeat(RouteBook.MAX_FAILURES) { book.recordMasqueFailure(MasqueRoute.HTTP3) }
        assertEquals(null, book.knownMasque(now))
    }

    @Test
    fun unreachableMasqueIsSkippedForADayAndClearedBySuccess() {
        val book = RouteBook()
        book.recordMasqueUnreachable(now)
        assertTrue(RouteBook.fromJson(book.toJson()).isMasqueFailing(now + 1000))
        assertFalse(book.isMasqueFailing(now + RouteBook.BAD_FAMILY_TTL_MS))
        book.recordMasqueSuccess(MasqueRoute.HTTP3, good = false, now)
        assertFalse(book.isMasqueFailing(now))
    }

    @Test
    fun booksSavedBeforeMasqueStillLoad() {
        val old = """{"routes":[],"bad":{"8.35.211.":$now}}"""
        val book = RouteBook.fromJson(old)
        assertTrue(book.isBadFamily("8.35.211.1:1", now))
        assertEquals(null, book.knownMasque(now))
    }

    @Test
    fun brokenJsonGivesAnEmptyBook() {
        assertTrue(RouteBook.fromJson("not json").known(now).isEmpty())
    }

    @Test
    fun familiesGroupAddresses() {
        assertEquals("162.159.192.", WarpEndpoints.familyOf("162.159.192.8:891"))
        assertEquals("162.159.192.", WarpEndpoints.familyOf("engage.cloudflareclient.com:2408"))
        assertEquals("v6", WarpEndpoints.familyOf("[2606:4700:d0::a29f:c001]:2408"))
    }

    @Test
    fun scanSampleCoversEveryFamily() {
        val families = WarpEndpoints.sample(32).map(WarpEndpoints::familyOf).toSet()
        assertTrue(families.containsAll(WarpEndpoints.prefixes))
    }

    @Test
    fun moscowExitCountsAsRussiaEvenThoughHelsinkiAlsoReportsRu() {
        val moscow = ExitInfo.parse("colo=DME\nloc=RU\nip=1.2.3.4\nwarp=on\n")!!
        val helsinki = ExitInfo.parse("colo=HEL\nloc=RU\nip=1.2.3.4\nwarp=on\n")!!
        assertTrue(moscow.inRussia)
        assertFalse(helsinki.inRussia)
        assertTrue(helsinki.warp)
    }
}
