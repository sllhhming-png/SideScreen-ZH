package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class VendorCallbackOwnerTest {
    @Test fun eligibleToIneligibleSetupKeepsNewCallbacksAndRejectsReleasedRound() {
        val oldCodec = Any(); val freshCodec = Any()
        val oldRound = VendorCallbackOwner(true)
        oldRound.bind(oldCodec)
        assertTrue(oldRound.accepts(oldCodec))
        oldRound.clear()
        val freshRound = VendorCallbackOwner(false)
        freshRound.bind(freshCodec)
        assertTrue(freshRound.accepts(freshCodec))
        assertFalse(oldRound.accepts(oldCodec))
        assertFalse(oldRound.accepts(freshCodec))
    }

    @Test fun vendorFallbackMayRebindWithinRoundButDoesNotRetargetAnotherRound() {
        val rejected = Any(); val fallback = Any(); val later = Any()
        val round = VendorCallbackOwner(true)
        round.bind(rejected)
        round.clear()
        round.bind(fallback)
        assertFalse(round.accepts(rejected))
        assertTrue(round.accepts(fallback))
        round.clear()
        val next = VendorCallbackOwner(true); next.bind(later)
        assertTrue(next.accepts(later))
        assertFalse(round.accepts(later))
        assertFalse(next.accepts(fallback))
    }
}
