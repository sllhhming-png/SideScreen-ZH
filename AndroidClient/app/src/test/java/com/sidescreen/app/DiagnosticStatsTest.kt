package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticStatsTest {
    // Breaks if zero/missing render callbacks are interpreted as zero latency or inferred from releases.
    @Test fun missingPresentationRemainsUnknown() {
        val stats = DiagnosticStats(3)
        val session = stats.beginSession(1_000_000)
        stats.received(session)
        stats.input(session, 2000)
        stats.output(session, 2000)
        stats.handedToSurface(session, 2_000, 7_000_000)
        val result = stats.snapshot()
        assertEquals(1L, result.received)
        assertEquals(1L, result.inputs)
        assertEquals(1L, result.outputs)
        assertEquals(5.0, result.handoff!!.p50Ms, 0.00001)
        assertNull(result.presentation)
        assertTrue(result.text("12", 31).contains("unknown"))
        assertTrue(result.text("12", 31).contains("Android 14"))
    }

    // Breaks if handoff latency uses callback arrival/render time rather than the local receive PTS.
    @Test fun separatesReceiveToSurfaceAndCarriedRenderTimestamp() {
        val stats = DiagnosticStats()
        val session = stats.beginSession(1_000_000)
        stats.handedToSurface(session, 2_000, 7_000_000)
        stats.presented(session, 2_000, 12_000_000)
        val result = stats.snapshot()
        assertEquals(5.0, result.handoff!!.p50Ms, 0.00001)
        assertEquals(10.0, result.presentation!!.p50Ms, 0.00001)
        assertEquals(1, result.presentation!!.count)
        assertTrue(result.text("14", 34).contains("端到端"))
    }

    // Breaks if reconnect mixes old counters, timestamps or codec/output metadata.
    @Test fun newSessionRejectsOldAndPreSessionFrames() {
        val stats = DiagnosticStats()
        val old = stats.beginSession(1_000_000)
        stats.codec(old, "codec-a", true, true)
        stats.outputSize(old, 2000, 1200)
        stats.handedToSurface(old, 2_000, 7_000_000)
        val fresh = stats.beginSession(20_000_000)
        stats.received(old)
        stats.received(fresh)
        stats.presented(old, 2_000, 25_000_000)
        stats.handedToSurface(fresh, 2_000, 25_000_000)
        val result = stats.snapshot()
        assertEquals(1L, result.received)
        assertNull(result.handoff)
        assertNull(result.presentation)
        assertNull(result.codecName)
        assertNull(result.width)
    }

    // Breaks if duplicates count twice, unmatched/invalid render callbacks become valid, or retention is unbounded.
    @Test fun samplesAreBoundedDeduplicatedAndMatched() {
        val stats = DiagnosticStats(3)
        val session = stats.beginSession(0)
        for (i in 1L..100L) {
            stats.handedToSurface(session, i * 1000, i * 1_000_000 + 5_000_000)
            stats.presented(session, i * 1000, i * 1_000_000 + 10_000_000)
            stats.presented(session, i * 1000, i * 1_000_000 + 11_000_000)
        }
        stats.presented(session, 999_000, 999_000_000)
        stats.presented(session, 100_000, 1)
        val result = stats.snapshot()
        assertEquals(100L, result.handoffs)
        assertEquals(3, result.handoff!!.count)
        assertEquals(3, result.presentation!!.count)
        assertEquals(10.0, result.presentation!!.p95Ms, 0.00001)
    }

    @Test fun decoderRestartClearsLocalSamplesAndRejectsOldPts() {
        val stats = DiagnosticStats()
        val session = stats.beginSession(0)
        stats.received(session)
        stats.handedToSurface(session, 1000, 6_000_000)
        stats.presented(session, 1000, 8_000_000)
        stats.resetDecoder(session, 20_000_000)
        stats.handedToSurface(session, 1000, 30_000_000)
        stats.presented(session, 1000, 40_000_000)
        val result = stats.snapshot()
        assertEquals(1L, result.received)
        assertEquals(0L, result.handoffs)
        assertNull(result.presentation)
        assertNull(result.handoff)
    }

    @Test fun inputAndOutputRejectPreRestartPtsAtomically() {
        val stats = DiagnosticStats()
        val session = stats.beginSession(0)
        stats.resetDecoder(session, 20_000_000)
        stats.input(session, 1000)
        stats.output(session, 1000)
        stats.input(session, 21_000)
        stats.output(session, 21_000)
        val result = stats.snapshot()
        assertEquals(1L, result.inputs)
        assertEquals(1L, result.outputs)
    }

    @Test fun disconnectedSessionRetainsSummaryAndRejectsLateCallbacksOrDecoderReset() {
        val stats = DiagnosticStats()
        val session = stats.beginSession(0)
        stats.received(session)
        stats.input(session, 1000)
        stats.output(session, 1000)
        stats.codec(session, "c2.vendor.hevc.decoder", true, true)
        stats.outputSize(session, 2000, 1200)
        stats.handedToSurface(session, 1000, 6_000_000)
        stats.presented(session, 1000, 9_000_000)
        val before = stats.snapshot()
        stats.endSession(session)
        stats.received(session)
        stats.input(session, 2000)
        stats.output(session, 2000)
        stats.codec(session, "obsolete", false, false)
        stats.outputSize(session, 1, 1)
        stats.handedToSurface(session, 2000, 10_000_000)
        stats.presented(session, 2000, 12_000_000)
        stats.resetDecoder(session, 20_000_000)
        assertEquals(before.copy(ended = true), stats.snapshot())
        assertTrue(stats.snapshot().text("12", 31).contains("已断开"))
    }

    @Test fun reconnectStartsSeparateSessionAndOldDisconnectCannotEndIt() {
        val stats = DiagnosticStats()
        val old = stats.beginSession(0)
        stats.received(old)
        stats.endSession(old)
        val fresh = stats.beginSession(20_000_000)
        stats.endSession(old)
        stats.received(fresh)
        val result = stats.snapshot()
        assertEquals(fresh, result.sessionId)
        assertFalse(result.ended)
        assertEquals(1L, result.received)
        assertNull(result.handoff)
    }

    @Test fun noSessionIsUnknownAndCannotBePopulatedByUnboundCallbacks() {
        val stats = DiagnosticStats()
        stats.received(0)
        stats.input(0, 1000)
        stats.output(0, 1000)
        stats.handedToSurface(0, 1000, 6_000_000)
        val result = stats.snapshot()
        assertEquals(0L, result.received)
        assertEquals(0L, result.inputs)
        assertEquals(0L, result.outputs)
        assertNull(result.handoff)
        assertTrue(result.text("12", 31).contains("未开始（unknown）"))
    }

    @Test fun reportUsesAllowlistedMetadataAndDoesNotInferUnknownCapability() {
        val stats = DiagnosticStats()
        val session = stats.beginSession(0)
        stats.codec(session, "c2.vendor.hevc.decoder", null, null)
        stats.outputSize(session, 2000, 1200)
        val text = stats.snapshot().text("12", 31)
        assertTrue(text.contains("c2.vendor.hevc.decoder"))
        assertTrue(text.contains("2000×1200"))
        assertTrue(text.contains("2000×1200@60: unknown"))
        assertFalse(text.contains("0.00 ms"))
    }
}
