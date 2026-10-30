package com.sidescreen.app

enum class DecoderConfigStep {
    VENDOR_INELIGIBLE, VENDOR_REQUESTED, VENDOR_ACCEPTED, VENDOR_CONFIGURE_FAILED_REBUILT, VENDOR_START_FAILED_REBUILT, VENDOR_RECOVERY_FAILED,
    FULL_ACCEPTED, FULL_REJECTED, BASIC_ACCEPTED, BASIC_REJECTED, MINIMAL_ACCEPTED, MINIMAL_REJECTED,
}
enum class VendorFailure { CONFIGURE, START }
data class VendorCodecTrial<T>(val codec: T, val accepted: Boolean, val failure: VendorFailure? = null)

/** Independent parameter policy; a successful configure is acceptance of a request, not proof of effect. */
object HisiLowLatencyRequest {
    fun parameters(diagnostic: Boolean, enabled: Boolean, api: Int, codec: String, mime: String): Map<String, Int> {
        if (!diagnostic || !enabled || api != 29 ||
            !codec.equals("OMX.hisi.video.decoder.hevc", ignoreCase = true) ||
            !mime.equals("video/hevc", ignoreCase = true)
        ) return emptyMap()
        return mapOf(
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
        )
    }

    fun fullParameters(rate: Int): Map<String, Int> = mapOf(
        "low-latency" to 1, "priority" to 0, "operating-rate" to rate, "max-bframes" to 0,
    )

    fun <T> configure(codec: T, vendor: Map<String, Int>, full: Map<String, Int>, configure: (T, Map<String, Int>) -> Unit, start: (T) -> Unit, release: (T) -> Unit, recreate: () -> T): VendorCodecTrial<T> {
        // Never mutate the standard settings: the fallback receives no vendor state.
        var phase = VendorFailure.CONFIGURE
        try {
            configure(codec, full + vendor)
            phase = VendorFailure.START
            start(codec)
        } catch (_: Exception) {
            // Failure to release or recreate propagates; continuing on a dirty codec is forbidden.
            release(codec)
            return VendorCodecTrial(recreate(), false, phase)
        }
        return VendorCodecTrial(codec, true)
    }
}
