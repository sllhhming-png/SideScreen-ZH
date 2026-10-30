package com.sidescreen.app

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class InputRecoveryPolicy(val requested: Boolean, val active: Boolean, val adaptive: Boolean?, val reason: String, val budgetNs: Long = 5_000_000) {
    companion object {
        fun select(requested: Boolean, api: Int, name: String, mime: String, adaptive: Boolean?): InputRecoveryPolicy {
            val target = api == 29 && name.equals("OMX.hisi.video.decoder.hevc", true) && mime == "video/hevc"
            val reason = when { !requested -> "开关关闭"; !target -> "设备/codec不匹配"; adaptive == null -> "adaptive能力unknown"; !adaptive -> "不支持adaptive"; else -> "目标codec且声明adaptive" }
            return InputRecoveryPolicy(requested, requested && target && adaptive == true, adaptive, reason)
        }
    }
}
enum class RecoveryStatus { QUEUED, WAIT_SYNC, EMPTY, TIMEOUT, CANCELLED, INTERRUPTED, QUEUE_EXCEPTION }
data class RecoveryResult(val status: RecoveryStatus, val immediateEmpty: Boolean = false, val waitNs: Long? = null, val rescued: Boolean = false, val threadSkipped: Boolean = false, val interrupted: Boolean = false)

/** No frame storage. Pool lock never calls native code; lifecycle -> pool is the only nested order. */
class InputRecoveryCoordinator<C : Any>(private val clock: () -> Long = System::nanoTime, private val await: (Condition, Long) -> Unit = { c,n -> c.awaitNanos(n); Unit }) {
    class Owner<C : Any> internal constructor(val codec: C, val policy: InputRecoveryPolicy, @Volatile var session: Long) {
        @Volatile internal var valid = true
        internal var lastRequestNs = 0L
        internal val sync = AtomicLong(1) // odd: waiting; version prevents a concurrent error being cleared by keyframe success.
    }
    class Lease<C : Any> internal constructor(val owner: Owner<C>, val session: Long, val index: Int) { internal var consumed = false }
    data class Acquisition<C : Any>(val lease: Lease<C>?, val result: RecoveryResult)
    private val lifecycle = ReentrantLock()
    private val pool = ReentrantLock()
    private val changed = pool.newCondition()
    private var current: Owner<C>? = null
    private val indices = LinkedHashSet<Int>()
    fun attach(codec: C, policy: InputRecoveryPolicy, session: Long): Owner<C> = lifecycle.withLock { pool.withLock {
        current?.valid = false; indices.clear(); Owner(codec,policy,session).also { current = it; changed.signalAll() }
    } }
    fun detach() = lifecycle.withLock { pool.withLock { current?.valid = false; current = null; indices.clear(); changed.signalAll() } }
    fun reconnect(session: Long) = lifecycle.withLock { pool.withLock {
        current?.let { it.session = session; if(it.policy.active) requireSync(it) }; changed.signalAll()
    } }
    fun cancel(session: Long) = lifecycle.withLock { pool.withLock { current?.takeIf { it.session == session }?.session = 0; changed.signalAll() } }
    fun owner(): Owner<C>? = pool.withLock { current }
    fun isNativeCurrent(owner: Owner<C>): Boolean = pool.withLock { owner.valid && current === owner }
    fun isCurrent(owner: Owner<C>, session: Long): Boolean = pool.withLock { valid(owner,session) }
    private fun valid(o: Owner<C>, session: Long) = o.valid && current === o && o.session == session
    fun offer(owner: Owner<C>, codec: C, index: Int) = pool.withLock {
        if(owner.valid && current === owner && owner.codec === codec) { indices.add(index); changed.signalAll() }; Unit
    }
    fun shouldRequest(owner: Owner<C>, session: Long, force: Boolean, now: Long): Boolean = pool.withLock {
        if(!valid(owner,session)) return false
        val interval = if(force) 200_000_000L else 1_000_000_000L
        if(now-owner.lastRequestNs < interval) return false
        owner.lastRequestNs = now
        true
    }
    fun requireSync(owner: Owner<C>) { if(owner.valid) owner.sync.updateAndGet { if(it % 2L == 0L) it + 1 else it + 2 } }
    fun acquire(owner: Owner<C>, session: Long, key: Boolean, allowWait: Boolean): Acquisition<C> = pool.withLock {
        if(!valid(owner,session)) return Acquisition(null,RecoveryResult(RecoveryStatus.CANCELLED))
        if(owner.sync.get() % 2L != 0L && !key) return Acquisition(null,RecoveryResult(RecoveryStatus.WAIT_SYNC))
        fun take(): Lease<C>? = indices.firstOrNull()?.let { indices.remove(it); Lease(owner,session,it) }
        take()?.let { return Acquisition(it,RecoveryResult(RecoveryStatus.QUEUED)) }
        if(!owner.policy.active) return Acquisition(null,RecoveryResult(RecoveryStatus.EMPTY,true))
        val start = clock(); val deadline = start + owner.policy.budgetNs
        if(allowWait) while(valid(owner,session) && indices.isEmpty()) {
            val remaining = deadline - clock(); if(remaining <= 0) break
            try { await(changed,remaining) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return Acquisition(null,RecoveryResult(RecoveryStatus.CANCELLED,true,(clock()-start).coerceAtLeast(0),interrupted=true)) }
        }
        val elapsed = (clock()-start).coerceAtLeast(0)
        if(!valid(owner,session)) return Acquisition(null,RecoveryResult(RecoveryStatus.CANCELLED,true,elapsed,threadSkipped=!allowWait))
        val lease = take()
        if(lease != null) return Acquisition(lease,RecoveryResult(RecoveryStatus.QUEUED,true,elapsed,true,!allowWait))
        Acquisition(null,RecoveryResult(RecoveryStatus.TIMEOUT,true,elapsed,threadSkipped=!allowWait))
    }
    fun submit(acquired: Acquisition<C>, key: Boolean, queue: (C,Int) -> Unit): RecoveryResult = lifecycle.withLock {
        val lease = acquired.lease
        if(lease == null) {
            // Validate timeouts under the same protection as native submit and detach.
            return acquired.result
        }
        if(lease.consumed) return RecoveryResult(RecoveryStatus.CANCELLED)
        lease.consumed = true
        val valid = pool.withLock {
            if(!valid(lease.owner,lease.session)) {
                // A cancelled reader has not touched native ownership: return only to this still-live native owner.
                if(lease.owner.valid && current === lease.owner) { indices.add(lease.index); changed.signalAll() }
                false
            } else true
        }
        if(!valid) return acquired.result.copy(status=RecoveryStatus.CANCELLED,rescued=false)
        val syncVersion = lease.owner.sync.get()
        if(!key && syncVersion % 2L != 0L) {
            pool.withLock { indices.add(lease.index); changed.signalAll() }
            return acquired.result.copy(status=RecoveryStatus.WAIT_SYNC,rescued=false)
        }
        try {
            queue(lease.owner.codec,lease.index)
            if(key && syncVersion % 2L != 0L) lease.owner.sync.compareAndSet(syncVersion,syncVersion+1)
            acquired.result
        } catch (_: Exception) {
            requireSync(lease.owner) // Native ownership is uncertain: never return this slot.
            acquired.result.copy(status=RecoveryStatus.QUEUE_EXCEPTION,rescued=false)
        }
    }
    fun process(owner: Owner<C>, session: Long, key: Boolean, allowWait: Boolean, releaseFrame: () -> Unit, queue: (C,Int) -> Unit): RecoveryResult {
        try {
            val acquired = acquire(owner,session,key,allowWait)
            if(acquired.result.interrupted) return lifecycle.withLock {
                if(owner.policy.active && isCurrent(owner,session)) {
                    requireSync(owner)
                    acquired.result.copy(status=RecoveryStatus.INTERRUPTED)
                } else acquired.result
            }
            if(acquired.result.status == RecoveryStatus.TIMEOUT) return lifecycle.withLock {
                if(!isCurrent(owner,session)) acquired.result.copy(status=RecoveryStatus.CANCELLED) else {
                    requireSync(owner); acquired.result
                }
            }
            return submit(acquired,key,queue)
        } finally { releaseFrame() }
    }
}
