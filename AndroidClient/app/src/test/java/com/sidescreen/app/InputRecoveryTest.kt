package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class InputRecoveryTest {
    private val active = InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc","video/hevc",true)
    private fun prime(c:InputRecoveryCoordinator<Any>, o:InputRecoveryCoordinator.Owner<Any>) { c.offer(o,o.codec,0); assertEquals(RecoveryStatus.QUEUED,c.process(o,o.session,true,true,{}, {_,_->}).status) }
    @Test fun timeoutStopsFollowingPFramesUntilSuccessfulSync() {
        var now=0L; val c=InputRecoveryCoordinator<Any>({now},{_,remaining->now+=remaining}); val o=c.attach(Any(),active,1); prime(c,o)
        assertEquals(RecoveryStatus.TIMEOUT,c.process(o,1,false,true,{}, {_,_->fail()}).status)
        c.offer(o,o.codec,1)
        assertEquals(RecoveryStatus.WAIT_SYNC,c.process(o,1,false,true,{}, {_,_->fail()}).status)
        assertEquals(RecoveryStatus.QUEUE_EXCEPTION,c.process(o,1,true,true,{}, {_,_->throw IllegalStateException()}).status)
        c.offer(o,o.codec,2); assertEquals(RecoveryStatus.WAIT_SYNC,c.process(o,1,false,true,{}, {_,_->fail()}).status)
        assertEquals(RecoveryStatus.QUEUED,c.process(o,1,true,true,{}, {_,_->}).status)
        c.offer(o,o.codec,3); assertEquals(RecoveryStatus.QUEUED,c.process(o,1,false,true,{}, {_,_->}).status)
    }
    @Test fun deadlineNotRenewedOnSpuriousWake() {
        var now=0L; val budgets=mutableListOf<Long>(); val c=InputRecoveryCoordinator<Any>({now},{_,remaining->budgets+=remaining;now+=1_000_000});val o=c.attach(Any(),active,1); prime(c,o)
        val result=c.process(o,1,false,true,{}, {_,_->fail()}); assertEquals(listOf(5_000_000L,4_000_000L,3_000_000L,2_000_000L,1_000_000L),budgets);assertEquals(5_000_000L,result.waitNs)
    }
    @Test fun callbackCanRescueAndDetachWakesOldWaiterWithoutEatingNewSlot() {
        val entered=CountDownLatch(1); val c=InputRecoveryCoordinator<Any>(await={condition,n->entered.countDown();condition.awaitNanos(n);Unit})
        val o=c.attach(Any(),active.copy(budgetNs=2_000_000_000),1);prime(c,o);val executor=Executors.newSingleThreadExecutor()
        try {
            val f=executor.submit<RecoveryResult>{ c.process(o,1,false,true,{}, {_,index->assertEquals(7,index)}) }
            assertTrue(entered.await(1,TimeUnit.SECONDS));c.offer(o,o.codec,7);assertTrue(f.get(1,TimeUnit.SECONDS).rescued)
            val waiting=CountDownLatch(1);val d=InputRecoveryCoordinator<Any>(await={condition,n->waiting.countDown();condition.awaitNanos(n);Unit});val old=d.attach(Any(),active.copy(budgetNs=2_000_000_000),1);prime(d,old)
            val stale=executor.submit<RecoveryResult>{d.process(old,1,false,true,{}, {_,_->fail()})};assertTrue(waiting.await(1,TimeUnit.SECONDS));d.detach();val fresh=d.attach(Any(),active,2);d.offer(fresh,fresh.codec,7)
            assertEquals(RecoveryStatus.CANCELLED,stale.get(1,TimeUnit.SECONDS).status);d.offer(old,old.codec,8);assertEquals(7,d.acquire(fresh,2,true,false).lease!!.index)
        } finally {executor.shutdownNow()}
    }
    @Test fun cancelledLeaseReturnsOnlyToSameNativeOwnerAndReconnectPreservesIdle() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active,1);c.offer(o,o.codec,3);c.offer(o,o.codec,4)
        val lease=c.acquire(o,1,true,false);c.reconnect(2)
        assertEquals(RecoveryStatus.CANCELLED,c.submit(lease,true){_,_->fail()}.status)
        assertEquals(RecoveryStatus.WAIT_SYNC,c.acquire(o,2,false,false).result.status)
        assertEquals(4,c.acquire(o,2,true,false).lease!!.index);assertEquals(3,c.acquire(o,2,true,false).lease!!.index)
        val old=c.acquire(o,2,true,false);c.detach();val fresh=c.attach(Any(),active,3);c.offer(fresh,fresh.codec,3);assertEquals(RecoveryStatus.TIMEOUT,old.result.status)
        c.submit(lease,true){_,_->fail()};assertEquals(3,c.acquire(fresh,3,true,false).lease!!.index)
    }
    @Test fun acquireBeforeTeardownCannotSubmitOrReturnIntoNewCodec() {
        val c=InputRecoveryCoordinator<Any>();val old=c.attach(Any(),active,1);c.offer(old,old.codec,1);val lease=c.acquire(old,1,true,false);c.detach();val new=c.attach(Any(),active,2);c.offer(new,new.codec,1)
        assertEquals(RecoveryStatus.CANCELLED,c.submit(lease,true){_,_->fail()}.status);assertEquals(1,c.acquire(new,2,true,false).lease!!.index)
    }
    @Test fun nativeSubmitDoesNotBlockInputCallbackAndDetachWaitsForSubmit() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active,1);c.offer(o,o.codec,0);val inNative=CountDownLatch(1);val resume=CountDownLatch(1);val e=Executors.newFixedThreadPool(3)
        try {val submit=e.submit{c.process(o,1,true,true,{}, {_,_->inNative.countDown();assertTrue(resume.await(2,TimeUnit.SECONDS))})};assertTrue(inNative.await(1,TimeUnit.SECONDS));e.submit{c.offer(o,o.codec,1)}.get(1,TimeUnit.SECONDS);val detach=e.submit{c.detach()};assertFalse(detach.isDone);resume.countDown();submit.get(1,TimeUnit.SECONDS);detach.get(1,TimeUnit.SECONDS);assertFalse(c.isCurrent(o,1))} finally{resume.countDown();e.shutdownNow()}
    }
    @Test fun legacyNoInputDoesNotRequireNewSyncAndEveryFrameReturnedOnce() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active.copy(active=false),1);prime(c,o);var returns=0
        assertEquals(RecoveryStatus.EMPTY,c.process(o,1,false,true,{returns++},{_,_->fail()}).status);c.offer(o,o.codec,0)
        assertEquals(RecoveryStatus.QUEUED,c.process(o,1,false,true,{returns++},{_,_->}).status);c.offer(o,o.codec,1)
        assertEquals(RecoveryStatus.QUEUE_EXCEPTION,c.process(o,1,false,true,{returns++},{_,_->throw IllegalStateException()}).status)
        assertEquals(RecoveryStatus.WAIT_SYNC,c.process(o,1,false,true,{returns++},{_,_->fail()}).status);c.detach()
        assertEquals(RecoveryStatus.CANCELLED,c.process(o,1,true,true,{returns++},{_,_->fail()}).status);assertEquals(5,returns)
    }
    @Test fun cancellationWakesSameCodecReaderAndReturnsUnsubmittedLease() {
        val entered=CountDownLatch(1);val c=InputRecoveryCoordinator<Any>(await={condition,n->entered.countDown();condition.awaitNanos(n);Unit});val o=c.attach(Any(),active.copy(budgetNs=2_000_000_000),1);prime(c,o)
        val e=Executors.newSingleThreadExecutor();var returned=0
        try {val waiter=e.submit<RecoveryResult>{c.process(o,1,false,true,{returned++},{_,_->fail()})};assertTrue(entered.await(1,TimeUnit.SECONDS));c.cancel(1);assertEquals(RecoveryStatus.CANCELLED,waiter.get(1,TimeUnit.SECONDS).status);assertEquals(1,returned);c.offer(o,o.codec,9);c.reconnect(2);assertEquals(9,c.acquire(o,2,true,false).lease!!.index)} finally{e.shutdownNow()}
    }
    @Test fun errorAfterAcquireRetainsSlotAndKeyframeSuccessCannotClearNewError() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active,1);prime(c,o);c.offer(o,o.codec,2);val acquired=c.acquire(o,1,false,false);c.requireSync(o)
        assertEquals(RecoveryStatus.WAIT_SYNC,c.submit(acquired,false){_,_->fail()}.status)
        val key=c.acquire(o,1,true,false);assertEquals(2,key.lease!!.index)
        c.submit(key,true){_,_->c.requireSync(o)}
        assertEquals(RecoveryStatus.WAIT_SYNC,c.acquire(o,1,false,false).result.status)
    }
    @Test fun forbiddenThreadNeverWaitsAndCancelledLeaseDoesNotReturnToDetachedOwner() {
        val c=InputRecoveryCoordinator<Any>(await={_,_->fail("must not wait")});val o=c.attach(Any(),active,1);prime(c,o)
        val result=c.process(o,1,false,false,{}, {_,_->fail()});assertTrue(result.threadSkipped);assertEquals(RecoveryStatus.TIMEOUT,result.status)
        c.offer(o,o.codec,8);val lease=c.acquire(o,1,true,false);c.cancel(1);c.reconnect(2);assertEquals(RecoveryStatus.CANCELLED,c.submit(lease,true){_,_->fail()}.status);assertEquals(8,c.acquire(o,2,true,false).lease!!.index)
    }
    @Test fun legacyRequestThrottleAndCancelledOwnerCannotRequest() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active.copy(active=false),1)
        assertTrue(c.shouldRequest(o,1,true,1_000_000_000));assertFalse(c.shouldRequest(o,1,true,1_100_000_000));assertTrue(c.shouldRequest(o,1,true,1_200_000_000))
        c.cancel(1);assertFalse(c.shouldRequest(o,1,true,2_000_000_000));c.reconnect(2);assertFalse(c.shouldRequest(o,1,true,3_000_000_000));assertTrue(c.shouldRequest(o,2,true,3_000_000_000))
    }
    @Test fun reconnectBetweenNativeSessionReadAndGuardMustNotLoseSlot() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active,1)
        val capturedSession=o.session
        c.reconnect(2)
        assertFalse("old network-session guard rejects a legitimate native callback",c.isCurrent(o,capturedSession))
        assertTrue("native callback acceptance must survive this interleaving",c.isNativeCurrent(o))
        c.offer(o,o.codec,5)
        assertEquals(5,c.acquire(o,2,true,false).lease!!.index)
        c.detach();val fresh=c.attach(Any(),active,3)
        assertFalse(c.isNativeCurrent(o));assertTrue(c.isNativeCurrent(fresh))
        c.offer(o,o.codec,5)
        assertNull(c.acquire(fresh,3,true,false).lease)
    }
    @Test fun nativeErrorBoundaryDoesNotDependOnCapturedConnectionSession() {
        val c=InputRecoveryCoordinator<Any>();val o=c.attach(Any(),active.copy(active=false),1);prime(c,o)
        val capturedSession=o.session;c.reconnect(2)
        assertFalse(c.isCurrent(o,capturedSession));assertTrue(c.isNativeCurrent(o))
        c.requireSync(o)
        assertEquals(RecoveryStatus.WAIT_SYNC,c.acquire(o,2,false,false).result.status)
        assertFalse(c.shouldRequest(o,capturedSession,true,1_000_000_000));assertTrue(c.shouldRequest(o,2,true,1_000_000_000))
    }
    @Test fun activeWaitInterruptIsDroppedFrameAndRequiresSuccessfulSync() {
        val c=InputRecoveryCoordinator<Any>(await={_,_->throw InterruptedException()});val o=c.attach(Any(),active,1);prime(c,o)
        try {
            val result=c.process(o,1,false,true,{}, {_,_->fail()})
            assertEquals(RecoveryStatus.INTERRUPTED,result.status);assertTrue(result.interrupted);assertTrue(Thread.currentThread().isInterrupted)
            c.offer(o,o.codec,1)
            assertEquals(RecoveryStatus.WAIT_SYNC,c.process(o,1,false,true,{}, {_,_->fail()}).status)
            assertEquals(RecoveryStatus.QUEUED,c.process(o,1,true,true,{}, {_,_->}).status)
            c.offer(o,o.codec,2);assertEquals(RecoveryStatus.QUEUED,c.process(o,1,false,true,{}, {_,_->}).status)
        } finally {Thread.interrupted()}
    }
    @Test fun detachedInterruptedWaitCannotChangeNewOwnerOrRequest() {
        lateinit var c: InputRecoveryCoordinator<Any>
        lateinit var fresh: InputRecoveryCoordinator.Owner<Any>
        c=InputRecoveryCoordinator(await={_,_->
            c.detach(); fresh=c.attach(Any(),active,2);c.offer(fresh,fresh.codec,1)
            throw InterruptedException()
        })
        val old=c.attach(Any(),active,1);prime(c,old)
        try {
            val result=c.process(old,1,false,true,{}, {_,_->fail()})
            assertEquals(RecoveryStatus.CANCELLED,result.status);assertTrue(result.interrupted);assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(c.shouldRequest(old,1,true,1_000_000_000))
            assertEquals(RecoveryStatus.QUEUED,c.process(fresh,2,true,true,{}, {_,index->assertEquals(1,index)}).status)
            c.offer(fresh,fresh.codec,2);assertEquals(RecoveryStatus.QUEUED,c.process(fresh,2,false,true,{}, {_,_->}).status)
        } finally {Thread.interrupted()}
    }
    @Test fun actualConditionInterruptPreservesBitReturnsFrameAndProtectsReferenceBoundary() {
        val entered=CountDownLatch(1)
        val c=InputRecoveryCoordinator<Any>(await={condition,n->entered.countDown();condition.awaitNanos(n);Unit})
        val o=c.attach(Any(),active.copy(budgetNs=2_000_000_000),1);prime(c,o)
        val result=java.util.concurrent.atomic.AtomicReference<RecoveryResult>()
        val interruptBit=java.util.concurrent.atomic.AtomicBoolean(false)
        val returned=java.util.concurrent.atomic.AtomicInteger()
        val worker=Thread {
            result.set(c.process(o,1,false,true,{returned.incrementAndGet()},{_,_->fail()}))
            interruptBit.set(Thread.currentThread().isInterrupted)
        }
        try {
            worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));worker.interrupt();worker.join(1_000)
            assertFalse(worker.isAlive);assertEquals(RecoveryStatus.INTERRUPTED,result.get().status)
            assertTrue(interruptBit.get());assertEquals(1,returned.get())
            c.offer(o,o.codec,3);assertEquals(RecoveryStatus.WAIT_SYNC,c.process(o,1,false,true,{}, {_,_->fail()}).status)
        } finally {c.detach();worker.interrupt();worker.join(1_000)}
    }
    @Test fun legacyNeverWaitsOrEntersInterruptedRecovery() {
        val c=InputRecoveryCoordinator<Any>(await={_,_->throw AssertionError("legacy must not await")});val o=c.attach(Any(),active.copy(active=false),1);prime(c,o)
        Thread.currentThread().interrupt()
        try {
            val result=c.process(o,1,false,true,{}, {_,_->fail()});assertEquals(RecoveryStatus.EMPTY,result.status);assertFalse(result.interrupted)
            c.offer(o,o.codec,1);assertEquals(RecoveryStatus.QUEUED,c.process(o,1,false,true,{}, {_,_->}).status)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted()}
    }
    @Test fun exactGateAndUnknownAdaptiveAreConservative() {
        assertTrue(InputRecoveryPolicy.select(true,29,"omx.HISI.video.decoder.hevc","video/hevc",true).active)
        for(p in listOf(InputRecoveryPolicy.select(false,29,"OMX.hisi.video.decoder.hevc","video/hevc",true),InputRecoveryPolicy.select(true,30,"OMX.hisi.video.decoder.hevc","video/hevc",true),InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc.extra","video/hevc",true),InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc","video/avc",true),InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc","video/hevc",null),InputRecoveryPolicy.select(true,29,"OMX.hisi.video.decoder.hevc","video/hevc",false))) assertFalse(p.active)
    }
}
