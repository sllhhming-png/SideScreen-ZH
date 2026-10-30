package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class PresentationSamplesTest {
    // Breaks if arrival order replaces the event's rendered timestamp, or duplicate PTS counts twice.
    @Test fun batchCallbacksAreSortedByCarriedTimeAndDeduplicated() {
        val samples = PresentationSamples(4)
        samples.record(30, 90_000)
        samples.record(10, 50_000)
        samples.record(20, 70_000)
        samples.record(20, 75_000)
        assertEquals(listOf(PresentedSample(10, 50_000), PresentedSample(20, 70_000), PresentedSample(30, 90_000)), samples.snapshot())
    }

    // Breaks if a callback captured before reset can repopulate the new session.
    @Test fun resetRejectsOldSessionCallbacks() {
        val samples = PresentationSamples()
        val old = samples.generation()
        samples.record(old, 10, 50_000)
        samples.reset()
        samples.record(old, 20, 70_000)
        assertTrue(samples.snapshot().isEmpty())
        samples.record(samples.generation(), 30, 90_000)
        assertEquals(1, samples.snapshot().size)
    }

    // Breaks if retention grows indefinitely or delayed/duplicate evicted records displace newer data.
    @Test fun keepsBoundedRecentEventsEvenWithLateCallbacks() {
        val samples = PresentationSamples(3)
        for (i in 1L..100L) samples.record(i, i * 10_000)
        samples.record(1, 10_000)
        assertEquals(listOf(98L, 99L, 100L), samples.snapshot().map { it.presentationTimeUs })
    }
}
