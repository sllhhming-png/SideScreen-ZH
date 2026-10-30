package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class StageObservationsTest {
    private fun receive(o: StageObservations, e: Long, ms: Long): Long { o.received(e,ms*1_000_000); return ms*1000 }
    private fun event(o: StageObservations,e:Long,pts:Long,s:ObservationStage,ms:Long) = o.event(e,pts,s,ms*1_000_000)

    @Test fun twoHundredMsSupplyGapsRemainVisibleWithEvenDownstreamOutput() {
        val o=StageObservations(); val e=o.reset()
        val pts=listOf(0L,200L,400L).map { receive(o,e,it) }
        pts.forEachIndexed { i,p -> event(o,e,p,ObservationStage.OUTPUT,500L+i*16); event(o,e,p,ObservationStage.HANDOFF,501L+i*16) }
        val s=o.snapshot()
        assertEquals(200.0,s.intervals[ObservationStage.RECEIVE]!!.p95Ms,0.00001)
        assertEquals(16.0,s.intervals[ObservationStage.OUTPUT]!!.p95Ms,0.00001)
        assertEquals(2,s.longIntervals.count { it.stage==ObservationStage.RECEIVE })
    }

    @Test fun uniformReceiveWithOutputAndLaterSurfaceGapsPreservesExactPtsContext() {
        val o=StageObservations(); val e=o.reset()
        val pts=listOf(0L,10L,20L).map { receive(o,e,it) }
        listOf(1L,11L,220L).forEachIndexed { i,t -> event(o,e,pts[i],ObservationStage.OUTPUT,t) }
        listOf(2L,12L,221L).forEachIndexed { i,t -> event(o,e,pts[i],ObservationStage.HANDOFF,t) }
        listOf(3L,13L,280L).forEachIndexed { i,t -> o.rendered(e,pts[i],t*1_000_000,(t+1)*1_000_000) }
        val s=o.snapshot()
        assertEquals(10.0,s.intervals[ObservationStage.RECEIVE]!!.maxMs,0.00001)
        assertEquals(209.0,s.intervals[ObservationStage.OUTPUT]!!.maxMs,0.00001)
        assertEquals(267.0,s.intervals[ObservationStage.RENDER]!!.maxMs,0.00001)
        val gap=s.longIntervals.first { it.stage==ObservationStage.OUTPUT }
        assertEquals(10_000L,gap.fromPtsUs); assertEquals(20_000L,gap.toPtsUs)
        assertEquals(10_000_000L,gap.receiveGapNs)
        assertNull(gap.handoffGapNs) // unavailable when the output event was observed; never filled from other endpoints.
    }

    @Test fun missingEventsAndInvalidArrivalRemainUnknownWithoutInventingFrames() {
        val o=StageObservations(); val e=o.reset()
        val p=receive(o,e,10)
        assertNull(o.snapshot().intervals[ObservationStage.OUTPUT])
        assertNull(o.snapshot().callbackLag)
        event(o,e,p,ObservationStage.HANDOFF,20)
        o.rendered(e,p,30_000_000,29_000_000)
        val s=o.snapshot()
        assertEquals(1,s.counts[ObservationStage.RENDER])
        assertEquals(0,s.counts[ObservationStage.CALLBACK])
        assertNull(s.callbackLag); assertNull(s.intervals[ObservationStage.RENDER])
    }

    @Test fun batchedNotificationsUseCarriedTimesForRenderIntervalsAndArrivalOnlyForLag() {
        val o=StageObservations(); val e=o.reset()
        val pts=listOf(0L,10L,20L).map { receive(o,e,it) }
        pts.forEachIndexed { i,p -> event(o,e,p,ObservationStage.HANDOFF,5L+i*10) }
        o.rendered(e,pts[2],70_000_000,200_000_000)
        o.rendered(e,pts[0],50_000_000,201_000_000)
        o.rendered(e,pts[1],60_000_000,202_000_000)
        val s=o.snapshot()
        assertEquals(10.0,s.intervals[ObservationStage.RENDER]!!.p95Ms,0.00001)
        assertEquals(1.0,s.intervals[ObservationStage.CALLBACK]!!.p95Ms,0.00001)
        assertEquals(142.0,s.callbackLag!!.p50Ms,0.00001)
    }

    @Test fun longIntervalsSurviveRollingWindowAndRemainBounded() {
        val o=StageObservations(capacity=3,eventCapacity=8); val e=o.reset()
        for(i in 0L..20L) receive(o,e,i*200)
        val s=o.snapshot()
        assertEquals(3,s.frames); assertEquals(8,s.longIntervals.size)
        assertEquals(4_000_000L,s.longIntervals.last().toPtsUs)
        assertTrue(s.counts.values.all { it<=3 })
    }

    @Test fun aStalledStageCanResumeAfterItsPreviousFrameLeftTheWindow() {
        val o=StageObservations(capacity=1); val e=o.reset()
        val old=receive(o,e,0); event(o,e,old,ObservationStage.OUTPUT,1)
        receive(o,e,10); val current=receive(o,e,20)
        event(o,e,current,ObservationStage.OUTPUT,201)
        val gap=o.snapshot().longIntervals.first { it.stage==ObservationStage.OUTPUT }
        assertEquals(old,gap.fromPtsUs); assertEquals(current,gap.toPtsUs)
        assertEquals(200_000_000L,gap.gapNs)
        assertNull(gap.receiveGapNs) // old endpoint has left the frame window.
    }

    @Test fun resetRejectsOldEpochEvenWhenPtsIsReusedAndClearsRetainedEvents() {
        val o=StageObservations(); val old=o.reset()
        receive(o,old,0); receive(o,old,200)
        val fresh=o.reset(); val p=receive(o,fresh,200)
        event(o,old,p,ObservationStage.OUTPUT,210)
        o.rendered(old,p,220_000_000,230_000_000)
        assertEquals(1,o.snapshot().frames)
        assertEquals(0,o.snapshot().counts[ObservationStage.OUTPUT])
        assertTrue(o.snapshot().longIntervals.isEmpty())
        event(o,fresh,p,ObservationStage.OUTPUT,215)
        assertEquals(1,o.snapshot().counts[ObservationStage.OUTPUT])
    }

    @Test fun equalCarriedTimestampUsesPtsTieBreakToAvoidDuplicateLongIntervals() {
        val o=StageObservations();val e=o.reset()
        val a=receive(o,e,0);val b=receive(o,e,1);val c=receive(o,e,4)
        listOf(a,b,c).forEach { event(o,e,it,ObservationStage.HANDOFF,5) }
        o.rendered(e,a,50_000_000,400_000_000)
        o.rendered(e,c,250_000_000,401_000_000)
        o.rendered(e,b,50_000_000,402_000_000)
        val gaps=o.snapshot().longIntervals.filter { it.stage==ObservationStage.RENDER }
        assertEquals(1,gaps.size)
        assertEquals(b,gaps.single().fromPtsUs)
        assertEquals(c,gaps.single().toPtsUs)
    }

    @Test fun evictedIntermediateNotificationsCannotBecomeWindowInterval() {
        val o=StageObservations(capacity=2);val e=o.reset()
        val a=receive(o,e,0);val b=receive(o,e,10)
        event(o,e,a,ObservationStage.HANDOFF,1);event(o,e,b,ObservationStage.HANDOFF,11)
        o.rendered(e,b,20_000_000,500_000_000)
        o.rendered(e,a,10_000_000,1000_000_000)
        val c=receive(o,e,1100);event(o,e,c,ObservationStage.HANDOFF,1101)
        o.rendered(e,c,1102_000_000,1200_000_000)
        val result=o.snapshot()
        assertEquals(2,result.counts[ObservationStage.CALLBACK])
        assertNull(result.intervals[ObservationStage.CALLBACK]) // known middle point is outside PTS cohort; never bridge 500→1200.
    }

    @Test fun callbackHistoryDoesNotBridgeAnAlreadyObservedPointEvictedByPts() {
        val o=StageObservations(capacity=2);val e=o.reset()
        val a=receive(o,e,0);val b=receive(o,e,10)
        event(o,e,a,ObservationStage.HANDOFF,1);event(o,e,b,ObservationStage.HANDOFF,11)
        o.rendered(e,b,20_000_000,500_000_000)
        o.rendered(e,a,10_000_000,1000_000_000)
        val c=receive(o,e,1100);event(o,e,c,ObservationStage.HANDOFF,1101)
        o.rendered(e,c,1102_000_000,1200_000_000)
        val gaps=o.snapshot().longIntervals.filter { it.stage==ObservationStage.CALLBACK }
        assertEquals(listOf(500_000_000L,200_000_000L),gaps.map { it.gapNs })
        assertFalse(gaps.any { it.fromNs==500_000_000L && it.toNs==1200_000_000L })
    }

    @Test fun multipleEvictedMiddlePointsCannotBeBridgedWhenLargestAnchorIsOutsideGap() {
        val o=StageObservations(capacity=3);val e=o.reset()
        val b=receive(o,e,0);event(o,e,b,ObservationStage.HANDOFF,1);o.rendered(e,b,300_000_000,1100_000_000)
        val c=receive(o,e,10);event(o,e,c,ObservationStage.HANDOFF,11);o.rendered(e,c,500_000_000,1101_000_000)
        val a=receive(o,e,20);event(o,e,a,ObservationStage.HANDOFF,21);o.rendered(e,a,100_000_000,1102_000_000)
        val d=receive(o,e,30);event(o,e,d,ObservationStage.HANDOFF,31);o.rendered(e,d,1000_000_000,1103_000_000)
        val x=receive(o,e,40);event(o,e,x,ObservationStage.HANDOFF,41);o.rendered(e,x,800_000_000,1104_000_000)
        val gaps=o.snapshot().longIntervals.filter { it.stage==ObservationStage.RENDER }
        assertFalse(gaps.any { it.fromNs==100_000_000L && it.toNs>=800_000_000L })
        assertTrue(gaps.any { it.fromNs==500_000_000L && it.toNs==800_000_000L })
        assertTrue(gaps.any { it.fromNs==800_000_000L && it.toNs==1000_000_000L })
    }

    @Test fun pointsOlderThanTimeTailCoverageCannotCreateGapsUsingWindowNeighbours() {
        val o=StageObservations(capacity=2);val e=o.reset()
        for(i in 0L..2L) {
            val p=receive(o,e,i*10);event(o,e,p,ObservationStage.OUTPUT,(i+1)*100)
        }
        val late=receive(o,e,30);event(o,e,late,ObservationStage.OUTPUT,50)
        val s=o.snapshot();val gaps=s.longIntervals.filter { it.stage==ObservationStage.OUTPUT }
        assertEquals(listOf(100_000_000L,100_000_000L),gaps.map { it.gapNs })
        assertFalse(gaps.any { it.fromNs==50_000_000L })
        // Both PTS points are in the window, but their adjacency chain is not covered by the time tail.
        assertEquals(2,s.counts[ObservationStage.OUTPUT])
        assertNull(s.intervals[ObservationStage.OUTPUT])
    }

    @Test fun outsideCoveragePointMayRepairRetainedTrueGapWithoutCreatingOtherNeighbours() {
        val o=StageObservations(capacity=2);val e=o.reset()
        val a=receive(o,e,0);event(o,e,a,ObservationStage.OUTPUT,10)
        val b=receive(o,e,10);event(o,e,b,ObservationStage.OUTPUT,510)
        val c=receive(o,e,20);event(o,e,c,ObservationStage.OUTPUT,610)
        val d=receive(o,e,30);event(o,e,d,ObservationStage.OUTPUT,710)
        val late=receive(o,e,40);event(o,e,late,ObservationStage.OUTPUT,100)
        val gaps=o.snapshot().longIntervals.filter { it.stage==ObservationStage.OUTPUT }
        assertFalse(gaps.any { it.fromNs==10_000_000L && it.toNs==510_000_000L })
        assertTrue(gaps.any { it.fromNs==100_000_000L && it.toNs==510_000_000L })
        assertFalse(gaps.any { it.fromNs==100_000_000L && it.toNs>510_000_000L })
    }

    @Test fun lateNotificationAfterItsPtsEvictionStillInterruptsTimeTailOnly() {
        val o=StageObservations(capacity=2);val e=o.reset()
        val a=receive(o,e,0);val b=receive(o,e,10)
        event(o,e,a,ObservationStage.HANDOFF,1);event(o,e,b,ObservationStage.HANDOFF,11)
        o.rendered(e,b,20_000_000,500_000_000)
        val c=receive(o,e,1100)
        o.rendered(e,a,10_000_000,1150_000_000) // valid callback, but PTS a is no longer in the receive window.
        event(o,e,c,ObservationStage.HANDOFF,1160)
        o.rendered(e,c,1161_000_000,1200_000_000)
        val s=o.snapshot();val gaps=s.longIntervals.filter { it.stage==ObservationStage.CALLBACK }
        assertEquals(listOf(650_000_000L),gaps.map { it.gapNs })
        assertEquals(2,s.counts[ObservationStage.CALLBACK])
        assertEquals(2,s.callbackLag!!.count)
        assertNull(s.intervals[ObservationStage.CALLBACK])
    }

    @Test fun lateOutputOrHandoffOutsidePtsWindowEntersOnlyTheTimeTail() {
        for(stage in listOf(ObservationStage.OUTPUT,ObservationStage.HANDOFF)) {
            val o=StageObservations(capacity=2);val e=o.reset()
            val a=receive(o,e,0);val b=receive(o,e,10)
            event(o,e,b,stage,500)
            val c=receive(o,e,1100)
            event(o,e,a,stage,1150)
            event(o,e,c,stage,1200)
            val s=o.snapshot()
            assertEquals(listOf(650_000_000L),s.longIntervals.filter { it.stage==stage }.map { it.gapNs })
            assertEquals(2,s.counts[stage])
            assertNull(s.intervals[stage])
        }
    }

    @Test fun lateBatchedCarriedEventsRepairProvisionalLongIntervals() {
        val o=StageObservations(); val e=o.reset()
        val pts=(0L..4L).map { receive(o,e,it) }
        pts.forEachIndexed { i,p -> event(o,e,p,ObservationStage.HANDOFF,i+5L) }
        o.rendered(e,pts[0],50_000_000,400_000_000)
        o.rendered(e,pts[4],250_000_000,401_000_000)
        for(i in 1..3) o.rendered(e,pts[i],(50L+i*50)*1_000_000,(401L+i)*1_000_000)
        assertEquals(50.0,o.snapshot().intervals[ObservationStage.RENDER]!!.maxMs,0.00001)
        assertTrue(o.snapshot().longIntervals.none { it.stage==ObservationStage.RENDER })
    }
}
