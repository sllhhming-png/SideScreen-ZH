package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class Diagnostic3IntegrationTest {
    @Test fun currentCodecWindowAndClassificationsRejectOldEpochAndPreResetReceive() {
        val stats=DiagnosticStats(); val session=stats.beginSession(0)
        val old=stats.observationEpoch(session)
        stats.received(session,1_000_000)
        stats.resetDecoder(session,2_000_000)
        val fresh=stats.observationEpoch(session)
        stats.received(session,1_000_000) // still a connection receive, but before this codec epoch.
        stats.received(session,3_000_000)
        stats.output(session,3000,4_000_000,old)
        stats.notQueued(session,3000,InputNotQueuedReason.NO_INPUT_BUFFER,old)
        val before=stats.snapshot()
        assertEquals(3L,before.received)
        assertEquals(1,before.observations!!.frames)
        assertEquals(0,before.observations!!.counts[ObservationStage.OUTPUT])
        assertEquals(0L,before.inputNotQueued[InputNotQueuedReason.NO_INPUT_BUFFER])
        stats.output(session,3000,4_000_000,fresh)
        assertEquals(1,stats.snapshot().observations!!.counts[ObservationStage.OUTPUT])
        assertNotEquals(old,fresh)
    }

    @Test fun rejectionReasonsCountRealFailedAttemptsButNotSuccessfulOrObsoleteOnes() {
        val stats=DiagnosticStats(); val session=stats.beginSession(0); val epoch=stats.observationEpoch(session)
        stats.received(session,1_000_000); stats.input(session,1000,epoch)
        InputNotQueuedReason.values().forEachIndexed { i,reason ->
            val ns=(i+2L)*1_000_000
            stats.received(session,ns)
            stats.notQueued(session,ns/1000,reason,epoch)
        }
        stats.staleOutput(session,2000,epoch)
        val result=stats.snapshot()
        assertEquals(1L,result.inputs)
        assertEquals(5L,result.inputNotQueued.values.sum())
        assertTrue(result.inputNotQueued.values.all { it==1L })
        assertEquals(1L,result.staleOutputDrops)
        stats.endSession(session)
        stats.notQueued(session,2000,InputNotQueuedReason.NO_INPUT_BUFFER,epoch)
        assertEquals(result.inputNotQueued,stats.snapshot().inputNotQueued)
        val next=stats.beginSession(20_000_000)
        stats.notQueued(session,30_000,InputNotQueuedReason.QUEUE_EXCEPTION,epoch)
        assertEquals(0L,stats.snapshot().inputNotQueued.values.sum())
        assertEquals(0L,stats.snapshot().staleOutputDrops)
        assertEquals(next,stats.snapshot().sessionId)
    }

    @Test fun evictedHandoffMatchStillAllowsValidCallbackTimelineWithoutChangingDelaySamples() {
        val stats=DiagnosticStats(2);val session=stats.beginSession(0);val e=stats.observationEpoch(session)
        stats.received(session,0);stats.received(session,10_000_000)
        stats.handedToSurface(session,0,1_000_000,e);stats.handedToSurface(session,10_000,11_000_000,e)
        stats.presented(session,10_000,20_000_000,500_000_000,e)
        stats.received(session,1100_000_000)
        stats.handedToSurface(session,1100_000,1101_000_000,e) // evicts old handoff PTS 0 too.
        stats.presented(session,0,10_000_000,1150_000_000,e)
        stats.presented(session,1100_000,1102_000_000,1200_000_000,e)
        val result=stats.snapshot()
        assertEquals(2,result.presentation!!.count) // old PTS must not enter the original matched latency samples.
        assertEquals(2,result.observations!!.counts[ObservationStage.CALLBACK])
        assertEquals(listOf(650_000_000L),result.observations!!.longIntervals.filter { it.stage==ObservationStage.CALLBACK }.map { it.gapNs })
        assertNull(result.observations!!.intervals[ObservationStage.CALLBACK])
    }

    @Test fun frozenSettingsSnapshotDoesNotChangeWhenCollectionContinuesIncludingNestedEvents() {
        val stats=DiagnosticStats(3); val session=stats.beginSession(0); val e=stats.observationEpoch(session)
        stats.received(session,0); stats.received(session,200_000_000)
        stats.output(session,200_000,201_000_000,e)
        val frozen=stats.snapshot()
        val frozenText=frozen.text("10",29,"diag3")
        assertEquals(1,frozen.observations!!.longIntervals.size)
        for(i in 2L..8L) {
            stats.received(session,i*200_000_000)
            stats.output(session,i*200_000, i*200_000_000+1_000_000,e)
            stats.notQueued(session,i*200_000,InputNotQueuedReason.NO_INPUT_BUFFER,e)
        }
        assertEquals(frozenText,frozen.text("10",29,"diag3"))
        assertEquals(2,frozen.observations!!.frames)
        assertEquals(1,frozen.observations!!.longIntervals.size)
        assertNotEquals(frozenText,stats.snapshot().text("10",29,"diag3"))
        assertEquals(3,stats.snapshot().observations!!.frames)
        assertTrue(stats.snapshot().observations!!.longIntervals.size>1)
    }
}
