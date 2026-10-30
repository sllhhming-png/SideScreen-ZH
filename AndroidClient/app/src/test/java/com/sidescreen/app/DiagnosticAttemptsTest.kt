package com.sidescreen.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DiagnosticAttemptsTest {
    @Test fun concurrentAttemptsCannotRetargetSessionWhileDecoderIsBinding() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        val binding = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Long?> {
                attempts.begin(0) {
                    binding.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(binding.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Long?> {
                contenderStarted.countDown()
                attempts.begin(20_000_000) { error("overlapping attempt must not bind a decoder") }
            }
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS))
            resume.countDown()
            val firstId = first.get(5, TimeUnit.SECONDS)!!
            assertNull(second.get(5, TimeUnit.SECONDS))
            assertEquals(firstId, stats.snapshot().sessionId)
            assertEquals(1L, attempts.rejectedCount())
            // Ending the stats alone must not unlock a still-unwinding connection coroutine.
            stats.endSession(firstId)
            assertNull(attempts.begin(30_000_000) {})
            attempts.finish(firstId)
            assertNotNull(attempts.begin(40_000_000) {})
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun oldAttemptFailureBeforeReconnectSuccessCannotFreezeOrReleaseNewAttempt() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        val old = attempts.begin(0) {}!!
        attempts.finish(old)
        val fresh = attempts.begin(20_000_000) {}!! // handshake has not succeeded yet
        stats.received(fresh)
        // Delayed old failure/cleanup arrive while the new attempt is still connecting.
        stats.endSession(old)
        attempts.finish(old)
        assertFalse(attempts.isCurrent(old))
        assertTrue(attempts.isCurrent(fresh))
        assertFalse(stats.snapshot().ended)
        assertEquals(1L, stats.snapshot().received)
        assertNull(attempts.begin(30_000_000) {})
        attempts.finish(fresh)
        assertTrue(stats.snapshot().ended)
    }

    @Test fun rejectedRetryWhileOldCoroutineIsUnwindingRestoresRetryableIdle() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        val old = attempts.begin(0) {}!!
        attempts.connectionStatus(old, true)
        stats.endSession(old) // UI already disconnected; coroutine has not reached finally.
        assertNull(attempts.begin(20_000_000) {})
        var restored: DiagnosticRejectedUiState? = null
        attempts.restoreRejectedUi { restored = it }
        assertEquals(DiagnosticRejectedUiState.IDLE, restored)
        attempts.finish(old)
        assertNotNull(attempts.begin(30_000_000) {})
    }

    @Test fun rejectedOverlapPreservesLiveConnectionAndIgnoresOldStatusCallbacks() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        val old = attempts.begin(0) {}!!
        attempts.finish(old)
        val live = attempts.begin(20_000_000) {}!!
        attempts.connectionStatus(live, true)
        attempts.connectionStatus(old, false)
        assertNull(attempts.begin(30_000_000) {})
        var restored: DiagnosticRejectedUiState? = null
        attempts.restoreRejectedUi { restored = it }
        assertEquals(DiagnosticRejectedUiState.CONNECTED, restored)
        assertFalse(stats.snapshot().ended)
    }

    @Test fun delayedRejectedUiUsesLatestSessionInsteadOfOldDisconnectedState() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        val old = attempts.begin(0) {}!!
        stats.endSession(old)
        assertNull(attempts.begin(10_000_000) {}) // rejection UI is queued, not yet applied.
        attempts.finish(old)
        val fresh = attempts.begin(20_000_000) {}!!
        var restored: DiagnosticRejectedUiState? = null
        attempts.restoreRejectedUi { restored = it }
        assertEquals(DiagnosticRejectedUiState.CONNECTING, restored)
        attempts.connectionStatus(fresh, true)
        attempts.restoreRejectedUi { restored = it }
        assertEquals(DiagnosticRejectedUiState.CONNECTED, restored)
    }

    @Test fun failedDecoderBindingDoesNotLeaveGateLockedOrReturnAnotherSessionsId() {
        val stats = DiagnosticStats()
        val attempts = DiagnosticAttempts(stats)
        try {
            attempts.begin(0) { throw IllegalStateException("bind failed") }
            fail("binding failure must propagate")
        } catch (_: IllegalStateException) {}
        assertTrue(stats.snapshot().ended)
        var boundId = 0L
        val id = attempts.begin(20_000_000) { boundId = it }
        assertEquals(boundId, id)
        assertEquals(id, stats.snapshot().sessionId)
    }
}
