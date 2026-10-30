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
    val outputDelay: DelaySummary? = null, val releaseCall: DelaySummary? = null, val afterSurface: DelaySummary? = null, val crop: DecoderCrop? = null,
    val configuration: List<DecoderConfigStep> = emptyList(),
    val observationEpoch: Long = 0,
    val observations: StageObservationSnapshot? = null,
    val inputNotQueued: Map<InputNotQueuedReason, Long> = emptyMap(),
    val staleOutputDrops: Long = 0,
) {
    /** Allowlist only: never append MediaFormat, exceptions, connection data or existing logs. */
    fun text(androidVersion: String, api: Int, appVersion: String = "unknown"): String = buildString {
        appendLine("Side Screen 中文诊断（非延迟修复/验收）")
        appendLine("应用版本: $appVersion")
        appendLine("Android $androidVersion / API $api")
        appendLine("会话: ${if (sessionId == 0L) "未开始（unknown）" else "#$sessionId " + if (ended) "已断开（保留最后汇总）" else "采集中（连接尝试 / 串流）"}")
        appendLine("卡顿检查摘要：当前codec观察epoch=$observationEpoch，接收PTS窗口=${observations?.frames ?: 0}/240")
        appendLine("同一PTS窗口内且邻接链已覆盖的真实事件间隔")
        for (stage in listOf(ObservationStage.RECEIVE,ObservationStage.OUTPUT,ObservationStage.HANDOFF,ObservationStage.RENDER)) {
            appendLine("${stage.label}间隔: ${compact(observations?.intervals?.get(stage))}")
        }
        appendLine("通知到达−携带时刻: ${compact(observations?.callbackLag)}")
        appendLine("未入队: ${InputNotQueuedReason.values().joinToString(" / ") { "${it.label}=${inputNotQueued[it] ?: 0}" }}; stale输出=$staleOutputDrops")
        appendLine("保留长间隔事件=${observations?.longIntervals?.size ?: 0}/8（≥100ms仅诊断触发，非验收门槛；明细在后文）")
        appendLine("间隔含静止/设置页/阶段丢弃或缺回调，非完整FPS/冻结；不同阶段分位数不能相减。")
        appendLine("新增窗口与分类按当前codec观察epoch重置；连接received保留，不能配平或推断网络丢包。")
        appendLine("重连会开始新会话并清空旧汇总，请先复制；仅当前进程内存保留，退出后不保留。")
        appendLine("实际 codec: ${codecName ?: "unknown"}")
        appendLine("配置尝试: ${configuration.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "unknown"}")
        appendLine("VENDOR_ACCEPTED 仅表示配置与启动成功；其他 ACCEPTED 表示配置被接受。硬件效果未经验证，API29 标准 low-latency 不保证生效。")
        appendLine("硬件加速: ${hardware?.toString() ?: "unknown（API 29 前不推断）"}")
        appendLine("该 codec 能力查询 2000×1200@60: ${supportsTarget?.toString() ?: "unknown"}（能力声明不等于实测）")
        appendLine("输出格式像素尺寸: ${if (width != null && height != null) "${width}×$height" else "unknown"}")
        val effective = if (width != null && height != null) crop?.effectiveSize(width, height) else null
        appendLine("crop(left,top,right,bottom): ${crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "unknown"}; 有效尺寸: ${effective?.let { "${it.first}×${it.second}" } ?: "unknown"}")
        appendLine("本连接累计完整收帧=$received；当前解码器: 入 codec=$inputs / 输出缓冲=$outputs / 交给 Surface=$handoffs")
        appendLine("本机完整收帧→输出回调入口（筛选前，含未交 Surface 的输出）: ${format(outputDelay)}")
        appendLine("releaseOutputBuffer(render=true) 调用耗时: ${format(releaseCall)}")
        appendLine("本机完整收帧→交给 Surface: ${format(handoff)}")
        appendLine("本机完整收帧→呈现回调携带时刻（仅实际回调样本）: ${format(presentation)}")
        appendLine("交给 Surface→呈现携带时刻（同 PTS 逐帧相减）: ${format(afterSurface)}")
        appendLine("Surface 样本受现有 100ms 丢弃策略筛选；呈现仅匹配当前 handoff 窗口，不能用分位数相减。")
        appendLine("延迟样本仅保留最近最多 240 帧；PTS 为本机收帧完成时间（微秒精度），交给 Surface 时刻取 render=true 释放调用前；不含采集、编码、网络。")
        appendLine("呈现回调完整性 / 完整呈现 FPS / 冻结: unknown（未验证全帧覆盖）")
        appendLine("呈现使用回调 nanoTime；Android 14 前可能缺回调，其他版本也需实机核对。缺回调保持 unknown，回调样本不能当完整呈现 FPS、冻结或端到端延迟。")
        appendLine("阶段间隔完整统计（同一PTS窗口内且邻接链已覆盖的真实事件间隔；通知迟到不会增大携带render时刻；同刻多个PTS不等于多个显示帧）")
        appendLine("间隔n仅计可确认相邻的点，可能小于阶段事件数−1；覆盖不足为unknown。")
        for (stage in ObservationStage.values()) {
            val count = observations?.counts?.get(stage) ?: 0
            val missing = (observations?.frames ?: 0)-count
            appendLine("${stage.label}: ${format(observations?.intervals?.get(stage))}; 阶段事件=$count，窗口缺项=$missing")
        }
        appendLine("通知滞后完整统计: ${format(observations?.callbackLag)}")
        appendLine("本epoch保留长间隔（会跨滚动窗口保留；触发时同PTS端点已知值，unknown未回填；不是冻结结论；端点为同机PTS µs）")
        val events = observations?.longIntervals.orEmpty()
        if(events.isEmpty()) appendLine("无保留事件（不证明无卡顿）")
        events.forEachIndexed { index,event ->
            appendLine("${index+1}. ${event.stage.label} PTS ${event.fromPtsUs}→${event.toPtsUs}，间隔=${ms(event.gapNs)} ms；同PTS端点当时已有 收/出/交=${ms(event.receiveGapNs)}/${ms(event.outputGapNs)}/${ms(event.handoffGapNs)} ms")
        }
        append("此文本仅含上述诊断数字与 codec 元数据；复制由用户主动触发。")
    }

    private fun compact(summary: DelaySummary?): String = summary?.let {
        String.format(Locale.US,"n=%d p95=%.2f max=%.2f ms",it.count,it.p95Ms,it.maxMs)
    } ?: "unknown（样本不足）"
    private fun ms(ns: Long?): String = ns?.let { String.format(Locale.US,"%.2f",it/1_000_000.0) } ?: "unknown"

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
    private var crop: DecoderCrop? = null
    private val configuration = ArrayList<DecoderConfigStep>(8)
    private val outputTimes = TreeMap<Long, Long>()
    private val releaseDurations = TreeMap<Long, Long>()
    private val handoffTimes = TreeMap<Long, Long>()
    private val presentation = PresentationSamples(capacity)
    private var evictedThroughUs = Long.MIN_VALUE
    private val stageObservations = StageObservations(capacity)
    private val inputReasons = LongArray(InputNotQueuedReason.values().size)
    private var staleOutputs = 0L

    @Synchronized fun beginSession(startNs: Long): Long {
        session++
        ended = false
        this.startNs = startNs
        received = 0; inputs = 0; outputs = 0; handoffs = 0
        codecName = null; hardware = null; supportsTarget = null; width = null; height = null
        crop = null
        configuration.clear()
        outputTimes.clear()
        releaseDurations.clear()
        handoffTimes.clear()
        presentation.reset()
        evictedThroughUs = Long.MIN_VALUE
        stageObservations.reset(); inputReasons.fill(0); staleOutputs = 0
        return session
    }

    @Synchronized fun resetDecoder(session: Long, startNs: Long): Long {
        if (session != this.session || session == 0L || ended) return 0L
        this.startNs = maxOf(this.startNs, startNs)
        inputs = 0; outputs = 0; handoffs = 0
        codecName = null; hardware = null; supportsTarget = null; width = null; height = null
        crop = null
        configuration.clear()
        outputTimes.clear()
        releaseDurations.clear()
        handoffTimes.clear()
        presentation.reset()
        evictedThroughUs = Long.MIN_VALUE
        inputReasons.fill(0); staleOutputs = 0
        return stageObservations.reset()
    }

    @Synchronized fun observationEpoch(session: Long): Long = if (session == this.session && session > 0 && !ended) stageObservations.epoch() else 0L
    @Synchronized fun notQueued(session: Long, ptsUs: Long, reason: InputNotQueuedReason, epoch: Long) {
        if (accepts(session,ptsUs,epoch)) inputReasons[reason.ordinal]++
    }
    @Synchronized fun staleOutput(session: Long, ptsUs: Long, epoch: Long) {
        if (accepts(session,ptsUs,epoch)) staleOutputs++
    }

    @Synchronized fun endSession(session: Long) {
        if (session == this.session && session > 0) ended = true
    }

    @Synchronized fun received(session: Long, receiveNs: Long? = null) {
        if (session != this.session || session == 0L || ended) return
        received++
        if(receiveNs != null && receiveNs >= startNs) stageObservations.received(stageObservations.epoch(),receiveNs)
    }
    @Synchronized fun input(session: Long, ptsUs: Long, epoch: Long? = null) {
        if (accepts(session, ptsUs, epoch)) inputs++
    }
    @Synchronized fun output(session: Long, ptsUs: Long, outputNs: Long? = null, epoch: Long? = null) {
        if (!accepts(session, ptsUs, epoch)) return
        outputs++
        if (outputNs != null && outputNs >= ptsUs * 1000) {
            putBounded(outputTimes, ptsUs, outputNs)
            stageObservations.event(stageObservations.epoch(),ptsUs,ObservationStage.OUTPUT,outputNs)
        }
    }

    @Synchronized fun codec(session: Long, name: String, hardware: Boolean?, supportsTarget: Boolean?) {
        if (session != this.session || session == 0L || ended) return
        codecName = name
        this.hardware = hardware
        this.supportsTarget = supportsTarget
    }

    @Synchronized fun outputSize(session: Long, width: Int, height: Int, crop: DecoderCrop? = null) {
        if (session != this.session || session == 0L || ended || width <= 0 || height <= 0) return
        this.width = width; this.height = height; this.crop = crop
    }

    @Synchronized private fun accepts(session: Long, ptsUs: Long, epoch: Long? = null): Boolean =
        session == this.session && session > 0 && !ended && (epoch == null || epoch == stageObservations.epoch()) && ptsUs >= 0 && ptsUs <= Long.MAX_VALUE / 1000 && ptsUs * 1000 >= startNs

    @Synchronized fun handedToSurface(session: Long, ptsUs: Long, handoffNs: Long, epoch: Long? = null) {
        if (!accepts(session, ptsUs, epoch) || handoffNs < ptsUs * 1000) return
        stageObservations.event(stageObservations.epoch(),ptsUs,ObservationStage.HANDOFF,handoffNs)
        // Preserve the original handoff latency/counter admission policy; only the independent tail sees older points.
        if (ptsUs <= evictedThroughUs || handoffTimes.containsKey(ptsUs)) return
        handoffs++
        handoffTimes[ptsUs] = handoffNs
        if (handoffTimes.size > capacity) evictedThroughUs = maxOf(evictedThroughUs, handoffTimes.pollFirstEntry()!!.key)
    }

    @Synchronized fun released(session: Long, ptsUs: Long, startNs: Long, endNs: Long, epoch: Long? = null) {
        if (!accepts(session, ptsUs, epoch) || handoffTimes[ptsUs] != startNs || endNs < startNs) return
        putBounded(releaseDurations, ptsUs, endNs - startNs)
    }

    @Synchronized fun configurationStep(session: Long, step: DecoderConfigStep) {
        if (session != this.session || session == 0L || ended) return
        if (configuration.size < 8) configuration.add(step)
    }

    private fun putBounded(map: TreeMap<Long, Long>, ptsUs: Long, value: Long) {
        if (!map.containsKey(ptsUs)) map[ptsUs] = value
        if (map.size > capacity) map.pollFirstEntry()
    }

    @Synchronized fun presented(session: Long, ptsUs: Long, renderedNs: Long, arrivalNs: Long? = null, epoch: Long? = null) {
        if (!accepts(session, ptsUs, epoch)) return
        val handoffNs = handoffTimes[ptsUs]
        if (handoffNs != null && renderedNs < handoffNs) return
        stageObservations.rendered(stageObservations.epoch(),ptsUs,renderedNs,arrivalNs,handoffNs)
        // A valid old notification still interrupts the time tail, but never invent a legacy matched delay sample.
        if (handoffNs == null) return
        presentation.record(presentation.generation(), ptsUs, renderedNs)
    }

    @Synchronized fun snapshot(): DiagnosticSnapshot {
        val matched = presentation.snapshot().filter { handoffTimes.containsKey(it.presentationTimeUs) }
        return DiagnosticSnapshot(
            received, inputs, outputs, handoffs, codecName, hardware, supportsTarget, width, height,
            summarize(handoffTimes.map { (pts, handoff) -> handoff - pts * 1000 }),
            summarize(matched.map { it.renderedNs - it.presentationTimeUs * 1000 }),
            session, ended,
            summarize(outputTimes.map { (pts, output) -> output - pts * 1000 }),
            summarize(releaseDurations.filterKeys { handoffTimes.containsKey(it) }.values.toList()),
            summarize(matched.map { it.renderedNs - handoffTimes.getValue(it.presentationTimeUs) }),
            crop, configuration.toList(),
            stageObservations.epoch(), stageObservations.snapshot(),
            InputNotQueuedReason.values().associateWith { inputReasons[it.ordinal] }, staleOutputs,
        )
    }

    private fun summarize(values: List<Long>): DelaySummary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2].toDouble() else sorted[n / 2 - 1] / 2.0 + sorted[n / 2] / 2.0
        return DelaySummary(n, median / 1_000_000.0, sorted[ceil(n * 0.95).toInt() - 1] / 1_000_000.0, sorted.last() / 1_000_000.0)
    }
}
