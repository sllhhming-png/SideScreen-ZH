package com.sidescreen.app

import java.util.Locale
import java.util.TreeMap
import kotlin.math.ceil

data class DelaySummary(val count: Int, val p50Ms: Double, val p95Ms: Double, val maxMs: Double)

data class DiagnosticSnapshot(
    val received: Long, val inputs: Long, val outputs: Long, val handoffs: Long,
    val codecName: String?, val hardware: Boolean?, val supportsTarget: Boolean?,
    val width: Int?, val height: Int?,
    val handoff: DelaySummary?, val presentation: DelaySummary?,
    val sessionId: Long = 0, val ended: Boolean = false,
) {
    /** Allowlist only: never append MediaFormat, exceptions, connection data or existing logs. */
    fun text(androidVersion: String, api: Int): String = buildString {
        appendLine("Side Screen 中文诊断（非延迟修复/验收）")
        appendLine("Android $androidVersion / API $api")
        appendLine("会话: ${if (sessionId == 0L) "未开始（unknown）" else "#$sessionId " + if (ended) "已断开（保留最后汇总）" else "采集中（连接尝试 / 串流）"}")
        appendLine("重连会开始新会话并清空旧汇总，请先复制；仅当前进程内存保留，退出后不保留。")
        appendLine("实际 codec: ${codecName ?: "unknown"}")
        appendLine("硬件加速: ${hardware?.toString() ?: "unknown（API 29 前不推断）"}")
        appendLine("该 codec 能力查询 2000×1200@60: ${supportsTarget?.toString() ?: "unknown"}（能力声明不等于实测）")
        appendLine("输出格式像素尺寸: ${if (width != null && height != null) "${width}×$height" else "unknown"}")
        appendLine("本连接累计完整收帧=$received；当前解码器: 入 codec=$inputs / 输出缓冲=$outputs / 交给 Surface=$handoffs")
        appendLine("本机完整收帧→交给 Surface: ${format(handoff)}")
        appendLine("本机完整收帧→呈现回调携带时刻（仅实际回调样本）: ${format(presentation)}")
        appendLine("延迟样本仅保留最近最多 240 帧；PTS 为本机收帧完成时间（微秒精度），交给 Surface 时刻取 render=true 释放调用前；不含采集、编码、网络。")
        appendLine("呈现回调完整性 / 完整呈现 FPS / 冻结: unknown（未验证全帧覆盖）")
        appendLine("呈现使用回调 nanoTime；Android 14 前可能缺回调，其他版本也需实机核对。缺回调保持 unknown，回调样本不能当完整呈现 FPS、冻结或端到端延迟。")
        append("此文本仅含上述诊断数字与 codec 元数据；复制由用户主动触发。")
    }

    private fun format(summary: DelaySummary?): String = summary?.let {
        String.format(Locale.US, "n=%d, p50=%.2f ms, p95=%.2f ms, max=%.2f ms", it.count, it.p50Ms, it.p95Ms, it.maxMs)
    } ?: "unknown（无有效样本）"
}

/** Numeric-only, bounded storage shared by the receive and codec threads. */
class DiagnosticStats(private val capacity: Int = 240) {
    init { require(capacity > 0) }
    private var session = 0L
    private var ended = false
    private var startNs = 0L
    private var received = 0L
    private var inputs = 0L
    private var outputs = 0L
    private var handoffs = 0L
    private var codecName: String? = null
    private var hardware: Boolean? = null
    private var supportsTarget: Boolean? = null
    private var width: Int? = null
    private var height: Int? = null
    private val handoffTimes = TreeMap<Long, Long>()
    private val presentation = PresentationSamples(capacity)
    private var evictedThroughUs = Long.MIN_VALUE

    @Synchronized fun beginSession(startNs: Long): Long {
        session++
        ended = false
        this.startNs = startNs
        received = 0; inputs = 0; outputs = 0; handoffs = 0
        codecName = null; hardware = null; supportsTarget = null; width = null; height = null
        handoffTimes.clear()
        presentation.reset()
        evictedThroughUs = Long.MIN_VALUE
        return session
    }

    @Synchronized fun resetDecoder(session: Long, startNs: Long) {
        if (session != this.session || session == 0L || ended) return
        this.startNs = maxOf(this.startNs, startNs)
        inputs = 0; outputs = 0; handoffs = 0
        codecName = null; hardware = null; supportsTarget = null; width = null; height = null
        handoffTimes.clear()
        presentation.reset()
        evictedThroughUs = Long.MIN_VALUE
    }

    @Synchronized fun endSession(session: Long) {
        if (session == this.session && session > 0) ended = true
    }

    @Synchronized fun received(session: Long) { if (session == this.session && session > 0 && !ended) received++ }
    @Synchronized fun input(session: Long, ptsUs: Long) {
        if (accepts(session, ptsUs)) inputs++
    }
    @Synchronized fun output(session: Long, ptsUs: Long) {
        if (accepts(session, ptsUs)) outputs++
    }

    @Synchronized fun codec(session: Long, name: String, hardware: Boolean?, supportsTarget: Boolean?) {
        if (session != this.session || session == 0L || ended) return
        codecName = name
        this.hardware = hardware
        this.supportsTarget = supportsTarget
    }

    @Synchronized fun outputSize(session: Long, width: Int, height: Int) {
        if (session != this.session || session == 0L || ended || width <= 0 || height <= 0) return
        this.width = width; this.height = height
    }

    @Synchronized private fun accepts(session: Long, ptsUs: Long): Boolean =
        session == this.session && session > 0 && !ended && ptsUs >= 0 && ptsUs <= Long.MAX_VALUE / 1000 && ptsUs * 1000 >= startNs

    @Synchronized fun handedToSurface(session: Long, ptsUs: Long, handoffNs: Long) {
        if (!accepts(session, ptsUs) || handoffNs < ptsUs * 1000 ||
            ptsUs <= evictedThroughUs || handoffTimes.containsKey(ptsUs)
        ) return
        handoffs++
        handoffTimes[ptsUs] = handoffNs
        if (handoffTimes.size > capacity) evictedThroughUs = maxOf(evictedThroughUs, handoffTimes.pollFirstEntry()!!.key)
    }

    @Synchronized fun presented(session: Long, ptsUs: Long, renderedNs: Long) {
        if (!accepts(session, ptsUs)) return
        val handoffNs = handoffTimes[ptsUs] ?: return
        if (renderedNs < handoffNs) return
        presentation.record(presentation.generation(), ptsUs, renderedNs)
    }

    @Synchronized fun snapshot(): DiagnosticSnapshot = DiagnosticSnapshot(
        received, inputs, outputs, handoffs, codecName, hardware, supportsTarget, width, height,
        summarize(handoffTimes.map { (pts, handoff) -> handoff - pts * 1000 }),
        summarize(presentation.snapshot().map { it.renderedNs - it.presentationTimeUs * 1000 }),
        session, ended,
    )

    private fun summarize(values: List<Long>): DelaySummary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2].toDouble() else sorted[n / 2 - 1] / 2.0 + sorted[n / 2] / 2.0
        return DelaySummary(n, median / 1_000_000.0, sorted[ceil(n * 0.95).toInt() - 1] / 1_000_000.0, sorted.last() / 1_000_000.0)
    }
}
