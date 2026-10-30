package com.sidescreen.app

/** Configuration belongs to a codec setup epoch, independently of network sessions (including session 0). */
class DecoderConfigurationHistory(private val capacity: Int = 8) {
    init { require(capacity > 0) }
    private var epoch = 0L
    private var active = false
    private val steps = ArrayList<DecoderConfigStep>(capacity)

    @Synchronized fun beginCodec(): Long {
        epoch++
        active = true
        steps.clear()
        return epoch
    }

    @Synchronized fun record(epoch: Long, step: DecoderConfigStep): Boolean {
        if (!active || epoch != this.epoch || steps.size >= capacity) return false
        steps.add(step)
        return true
    }

    @Synchronized fun replayInto(stats: DiagnosticStats, session: Long) {
        if (active) steps.forEach { stats.configurationStep(session, it) }
    }

    @Synchronized fun release() {
        active = false
        steps.clear()
    }
}
