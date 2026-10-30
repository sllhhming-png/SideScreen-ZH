package com.sidescreen.app

import java.util.TreeMap

data class PresentedSample(val presentationTimeUs: Long, val renderedNs: Long)

/** Bounded recent events. PTS is the client's monotonic receive clock, not the host's clock. */
class PresentationSamples(private val capacity: Int = 240) {
    init { require(capacity > 0) }
    private val samples = TreeMap<Long, PresentedSample>()
    private var session = 0L
    private var evictedThroughUs = Long.MIN_VALUE

    @Synchronized fun generation(): Long = session

    @Synchronized fun record(presentationTimeUs: Long, renderedNs: Long) {
        record(session, presentationTimeUs, renderedNs)
    }

    @Synchronized fun record(session: Long, presentationTimeUs: Long, renderedNs: Long) {
        if (session != this.session || presentationTimeUs < 0 || renderedNs < 0 ||
            presentationTimeUs <= evictedThroughUs || samples.containsKey(presentationTimeUs)
        ) return
        samples[presentationTimeUs] = PresentedSample(presentationTimeUs, renderedNs)
        if (samples.size > capacity) evictedThroughUs = maxOf(evictedThroughUs, samples.pollFirstEntry()!!.key)
    }

    @Synchronized fun snapshot(): List<PresentedSample> = samples.values.sortedBy { it.renderedNs }

    @Synchronized fun reset() {
        session++
        samples.clear()
        evictedThroughUs = Long.MIN_VALUE
    }
}
