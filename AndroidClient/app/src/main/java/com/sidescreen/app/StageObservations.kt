package com.sidescreen.app

enum class ObservationStage(val label: String) { RECEIVE("收帧"), OUTPUT("输出入口"), HANDOFF("交给Surface"), RENDER("呈现携带"), CALLBACK("通知入口") }
enum class InputNotQueuedReason(val label: String) { NOT_RUNNING("未运行"), CODEC_MISSING("codec缺失"), WAITING_KEYFRAME("等关键帧"), NO_INPUT_BUFFER("无input缓冲"), QUEUE_EXCEPTION("入队异常") }
data class LongIntervalEvent(val stage: ObservationStage, val fromPtsUs: Long, val toPtsUs: Long, val fromNs: Long, val toNs: Long, val receiveGapNs: Long?, val outputGapNs: Long?, val handoffGapNs: Long?) { val gapNs: Long get() = toNs - fromNs }
data class StageObservationSnapshot(val frames: Int, val counts: Map<ObservationStage, Int>, val intervals: Map<ObservationStage, DelaySummary?>, val callbackLag: DelaySummary?, val longIntervals: List<LongIntervalEvent>)
/** One receive-PTS window plus a small independently retained event history. No per-frame window sort/copy. */
class StageObservations(private val capacity: Int = 240, private val eventCapacity: Int = 8, private val thresholdNs: Long = 100_000_000) {
    init { require(capacity > 0 && eventCapacity > 0 && thresholdNs > 0) }
    private data class Point(val pts: Long, val ns: Long) : Comparable<Point> {
        override fun compareTo(other: Point): Int = ns.compareTo(other.ns).takeIf { it != 0 } ?: pts.compareTo(other.pts)
    }
    private data class GapKey(val endNs: Long, val stage: Int, val fromPts: Long, val toPts: Long) : Comparable<GapKey> {
        override fun compareTo(other: GapKey): Int = compareValuesBy(this,other,{it.endNs},{it.stage},{it.fromPts},{it.toPts})
    }
    private class Frame { val times = arrayOfNulls<Long>(ObservationStage.values().size) }
    private var token = 0L
    private var evictedThrough = Long.MIN_VALUE
    private val frames = java.util.TreeMap<Long,Frame>()
    private val ordered = Array(ObservationStage.values().size) { java.util.TreeSet<Point>() }
    // Independent of PTS eviction: keep the latest event-time points for provable chronological adjacency.
    private val timeTail = Array(ObservationStage.values().size) { java.util.TreeSet<Point>() }
    // This is only an index of the same bounded tail Points, not an unbounded all-session dedup table.
    private val tailByPts = Array(ObservationStage.values().size) { HashMap<Long,Point>() }
    private val timeEvictedThrough = arrayOfNulls<Point>(ObservationStage.values().size)
    private val gaps = java.util.TreeMap<GapKey,LongIntervalEvent>()

    @Synchronized fun reset(): Long {
        token++
        frames.clear(); ordered.forEach { it.clear() }; timeTail.forEach { it.clear() }; tailByPts.forEach { it.clear() }; timeEvictedThrough.fill(null); gaps.clear()
        evictedThrough = Long.MIN_VALUE
        return token
    }
    @Synchronized fun epoch(): Long = token

    @Synchronized fun received(epoch: Long, receiveNs: Long) {
        if (epoch != token || token == 0L || receiveNs < 0) return
        val pts = receiveNs / 1000
        if (pts <= evictedThrough || frames.containsKey(pts)) return
        frames[pts] = Frame()
        store(pts,ObservationStage.RECEIVE,receiveNs)
        if (frames.size > capacity) {
            val removed = frames.pollFirstEntry()!!
            removed.value.times.forEachIndexed { i,ns -> if(ns != null) ordered[i].remove(Point(removed.key,ns)) }
            evictedThrough = maxOf(evictedThrough,removed.key)
        }
    }

    @Synchronized fun event(epoch: Long, ptsUs: Long, stage: ObservationStage, eventNs: Long) {
        if (epoch != token || token == 0L || stage == ObservationStage.RECEIVE || stage == ObservationStage.RENDER || stage == ObservationStage.CALLBACK) return
        if (ptsUs < 0 || ptsUs > Long.MAX_VALUE / 1000) return
        val receiveNs = frames[ptsUs]?.times?.get(ObservationStage.RECEIVE.ordinal) ?: ptsUs * 1000
        if (eventNs < receiveNs) return
        store(ptsUs,stage,eventNs) // absent PTS: tail only, never rebuild a Frame.

    }

    @Synchronized fun rendered(epoch: Long, ptsUs: Long, renderedNs: Long, arrivalNs: Long?, knownHandoffNs: Long? = null) {
        if (epoch != token || token == 0L || ptsUs < 0 || ptsUs > Long.MAX_VALUE / 1000) return
        val frame = frames[ptsUs]
        if (frame?.times?.get(ObservationStage.RENDER.ordinal) != null ||
            tailByPts[ObservationStage.RENDER.ordinal].containsKey(ptsUs) ||
            tailByPts[ObservationStage.CALLBACK.ordinal].containsKey(ptsUs)) return
        val receiveNs = frame?.times?.get(ObservationStage.RECEIVE.ordinal) ?: ptsUs * 1000
        val currentHandoff = frame?.times?.get(ObservationStage.HANDOFF.ordinal)
        val handed = currentHandoff ?: knownHandoffNs
        if (renderedNs < receiveNs || (handed != null && renderedNs < handed)) return
        // Old PTS may be valid observations after Frame/handoff-sample eviction. Do not add them to window counts or lag.
        val updateWindow = frame != null && currentHandoff != null
        store(ptsUs,ObservationStage.RENDER,renderedNs,updateWindow)
        if (arrivalNs != null && arrivalNs >= renderedNs) store(ptsUs,ObservationStage.CALLBACK,arrivalNs,updateWindow)
    }

    private fun store(pts: Long, stage: ObservationStage, ns: Long, updateWindow: Boolean = true) {
        val frame = if(updateWindow) frames[pts] else null
        if (frame?.times?.get(stage.ordinal) != null || tailByPts[stage.ordinal].containsKey(pts)) return
        val point = Point(pts,ns)
        if(frame != null) {
            frame.times[stage.ordinal] = ns
            ordered[stage.ordinal].add(point)
        }
        // Late/batched carried events can fill an apparent gap. Repair only the at-most-eight retained events.
        val repaired = gaps.values.filter { it.stage == stage && Point(it.fromPtsUs,it.fromNs) < point && point < Point(it.toPtsUs,it.toNs) }
        for (gap in repaired) {
            gaps.remove(key(gap))
            saveGap(stage,Point(gap.fromPtsUs,gap.fromNs),point)
            saveGap(stage,point,Point(gap.toPtsUs,gap.toNs))
        }
        val floor = timeEvictedThrough[stage.ordinal]
        // Outside coverage, the point may repair retained true gaps above, but must not invent new neighbours.
        if (floor != null && point <= floor) return
        val tail = timeTail[stage.ordinal]
        tail.add(point)
        tailByPts[stage.ordinal][pts] = point
        tail.lower(point)?.let { saveGap(stage,it,point) }
        tail.higher(point)?.let { saveGap(stage,point,it) }
        // Evict only by event-time order, after testing adjacency (capacity=1 still retains a resumption edge).
        if (tail.size > capacity) {
            val removed = tail.pollFirst()
            tailByPts[stage.ordinal].remove(removed.pts)
            timeEvictedThrough[stage.ordinal] = removed
        }

    }

    private fun key(event: LongIntervalEvent) = GapKey(event.toNs,event.stage.ordinal,event.fromPtsUs,event.toPtsUs)
    private fun paired(stage: ObservationStage, from: Point, to: Point): Long? {
        val a = frames[from.pts]?.times?.get(stage.ordinal) ?: return null
        val b = frames[to.pts]?.times?.get(stage.ordinal) ?: return null
        return (b-a).takeIf { it >= 0 }
    }
    private fun saveGap(stage: ObservationStage, from: Point, to: Point) {
        if (to.ns < from.ns || to.ns-from.ns < thresholdNs) return
        val eventKey = GapKey(to.ns,stage.ordinal,from.pts,to.pts)
        if (!gaps.containsKey(eventKey)) gaps[eventKey] = LongIntervalEvent(stage,from.pts,to.pts,from.ns,to.ns,
            paired(ObservationStage.RECEIVE,from,to), paired(ObservationStage.OUTPUT,from,to), paired(ObservationStage.HANDOFF,from,to))
        if (gaps.size > eventCapacity) gaps.pollFirstEntry()
    }

    @Synchronized fun snapshot(): StageObservationSnapshot {
        val stages = ObservationStage.values()
        val intervals = stages.associateWith { stage ->
            val deltas = ArrayList<Long>()
            var previous: Point? = null
            for (point in timeTail[stage.ordinal]) {
                previous?.let { before ->
                    val fromPresent = frames[before.pts]?.times?.get(stage.ordinal) == before.ns
                    val toPresent = frames[point.pts]?.times?.get(stage.ordinal) == point.ns
                    if (fromPresent && toPresent) deltas.add(point.ns-before.ns)
                }
                // Do not skip an out-of-window intermediate point and connect its neighbours.
                previous = point
            }
            summary(deltas)
        }
        val lag = frames.values.mapNotNull { frame ->
            val render = frame.times[ObservationStage.RENDER.ordinal]
            val arrival = frame.times[ObservationStage.CALLBACK.ordinal]
            if(render == null || arrival == null) null else arrival-render
        }
        return StageObservationSnapshot(frames.size,stages.associateWith { ordered[it.ordinal].size },intervals,summary(lag),gaps.values.toList())
    }
    private fun summary(values: List<Long>): DelaySummary? {
        if(values.isEmpty()) return null
        val sorted = values.sorted(); val n = sorted.size
        val median = if(n%2==1) sorted[n/2].toDouble() else sorted[n/2-1]/2.0 + sorted[n/2]/2.0
        return DelaySummary(n,median/1_000_000.0,sorted[kotlin.math.ceil(n*0.95).toInt()-1]/1_000_000.0,sorted.last()/1_000_000.0)
    }
}
