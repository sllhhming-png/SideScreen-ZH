package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class DecoderConfigurationHistoryTest {
    @Test fun setupBeforeSessionAndSameCodecReconnectRestoreConfigurationAfterStatsReset() {
        val history = DecoderConfigurationHistory()
        val stats = DiagnosticStats()
        val codec = history.beginCodec()
        history.record(codec,DecoderConfigStep.VENDOR_REQUESTED)
        history.record(codec,DecoderConfigStep.VENDOR_ACCEPTED)
        history.replayInto(stats,0) // setup may occur before the first connected session.
        assertTrue(stats.snapshot().configuration.isEmpty())
        val first = stats.beginSession(0)
        history.replayInto(stats,first)
        assertEquals(listOf(DecoderConfigStep.VENDOR_REQUESTED,DecoderConfigStep.VENDOR_ACCEPTED),stats.snapshot().configuration)
        val reconnect = stats.beginSession(20_000_000)
        assertTrue(stats.snapshot().configuration.isEmpty())
        history.replayInto(stats,reconnect)
        assertEquals(listOf(DecoderConfigStep.VENDOR_REQUESTED,DecoderConfigStep.VENDOR_ACCEPTED),stats.snapshot().configuration)
    }

    @Test fun replacementAndReleaseRejectOldCodecStepsAndNeverReplayReleasedConfiguration() {
        val history = DecoderConfigurationHistory()
        val stats = DiagnosticStats()
        val old = history.beginCodec()
        history.record(old,DecoderConfigStep.VENDOR_ACCEPTED)
        val fresh = history.beginCodec()
        history.record(old,DecoderConfigStep.MINIMAL_REJECTED)
        history.record(fresh,DecoderConfigStep.FULL_ACCEPTED)
        val session = stats.beginSession(0)
        history.replayInto(stats,session)
        assertEquals(listOf(DecoderConfigStep.FULL_ACCEPTED),stats.snapshot().configuration)
        history.release()
        history.record(fresh,DecoderConfigStep.VENDOR_ACCEPTED)
        val reconnect = stats.beginSession(20_000_000)
        history.replayInto(stats,reconnect)
        assertTrue(stats.snapshot().configuration.isEmpty())
    }

    @Test fun configurationHistoryHasFiniteCapacity() {
        val history = DecoderConfigurationHistory(2)
        val codec = history.beginCodec()
        history.record(codec,DecoderConfigStep.VENDOR_REQUESTED)
        history.record(codec,DecoderConfigStep.VENDOR_START_FAILED_REBUILT)
        history.record(codec,DecoderConfigStep.FULL_ACCEPTED)
        val stats = DiagnosticStats(); val session = stats.beginSession(0)
        history.replayInto(stats,session)
        assertEquals(2,stats.snapshot().configuration.size)
    }
}
