package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class InputRecoveryStatsTest {
    @Test fun activeInterruptIsSeparateFromCancellationTimeoutAndQueueFailure() {
        val stats=DiagnosticStats();val s=stats.beginSession(0);val epoch=stats.observationEpoch(s)
        stats.recoveryResult(s,1,epoch,RecoveryResult(RecoveryStatus.INTERRUPTED,true,2_000_000,interrupted=true))
        val snapshot=stats.snapshot();assertEquals(listOf(1L,0L,0L,0L,0L,1L),snapshot.recoveryCounts)
        assertTrue(snapshot.inputNotQueued.values.all{it==0L});assertTrue(snapshot.text("10",29).contains("活跃等待中断=1"))
    }
    @Test fun boundedSamplesCountersAndFrozenSnapshotAreSessionAndEpochIsolated() {
        val stats=DiagnosticStats(2); val s=stats.beginSession(0); var epoch=stats.observationEpoch(s)
        val policy=InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc","video/hevc",true)
        stats.recoveryPolicy(s,policy)
        stats.recoveryResult(s,1,epoch,RecoveryResult(RecoveryStatus.QUEUED,true,1_000_000,true))
        val frozen=stats.snapshot();val text=frozen.text("10",29,"diag4")
        stats.recoveryResult(s,2,epoch,RecoveryResult(RecoveryStatus.TIMEOUT,true,2_000_000))
        stats.recoveryResult(s,3,epoch,RecoveryResult(RecoveryStatus.CANCELLED,true,3_000_000,threadSkipped=true))
        val live=stats.snapshot();assertEquals(listOf(3L,1L,1L,1L,1L,0L),live.recoveryCounts);assertEquals(2,live.recoveryWait!!.count);assertEquals(3.0,live.recoveryWait!!.maxMs,0.0)
        assertEquals(text,frozen.text("10",29,"diag4"));assertEquals(listOf(1L,1L,0L,0L,0L,0L),frozen.recoveryCounts)
        epoch=stats.resetDecoder(s,4_000);stats.recoveryResult(s,5,epoch-1,RecoveryResult(RecoveryStatus.TIMEOUT,true,5_000_000))
        assertNull(stats.snapshot().recoveryPolicy);assertTrue(stats.snapshot().recoveryCounts.all{it==0L});assertNull(stats.snapshot().recoveryWait)
        // Codec-owned metadata can be replayed to a new connection, never counters or samples.
        val next=stats.beginSession(5_000);stats.recoveryPolicy(next,policy);stats.recoveryResult(s,10,epoch,RecoveryResult(RecoveryStatus.TIMEOUT,true,5_000_000))
        assertEquals(policy,stats.snapshot().recoveryPolicy);assertTrue(stats.snapshot().recoveryCounts.all{it==0L})
    }
}
