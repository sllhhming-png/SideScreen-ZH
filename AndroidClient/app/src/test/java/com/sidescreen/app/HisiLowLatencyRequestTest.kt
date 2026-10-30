package com.sidescreen.app

import org.junit.Assert.*
import org.junit.Test

class HisiLowLatencyRequestTest {
    private val req = "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"
    private val rdy = "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"
    private val name = "OMX.hisi.video.decoder.hevc"

    @Test fun onlyExactDiagnosticApi29HevcDecoderCanRequestVendorParameters() {
        assertEquals(mapOf(req to 1, rdy to -1), HisiLowLatencyRequest.parameters(true, true, 29, name.uppercase(), "video/hevc"))
        for ((diagnostic, enabled, api, codec, mime) in listOf(
            Gate(false,true,29,name,"video/hevc"), Gate(true,false,29,name,"video/hevc"),
            Gate(true,true,28,name,"video/hevc"), Gate(true,true,30,name,"video/hevc"),
            Gate(true,true,29,"$name.extra","video/hevc"), Gate(true,true,29,name,"video/avc"),
        )) assertTrue(HisiLowLatencyRequest.parameters(diagnostic,enabled,api,codec,mime).isEmpty())
    }

    @Test fun rejectedVendorConfigurationReleasesBeforeRecreateAndLeavesFallbackClean() {
        val events = mutableListOf<String>()
        val full = HisiLowLatencyRequest.fullParameters(60)
        assertEquals(mapOf("low-latency" to 1,"priority" to 0,"operating-rate" to 60,"max-bframes" to 0), full)
        val vendor = HisiLowLatencyRequest.parameters(true,true,29,name,"video/hevc")
        val result = HisiLowLatencyRequest.configure(1, vendor, full,
            configure = { id, params ->
                events += "configure$id"
                assertEquals(1, params[req]); assertEquals(-1, params[rdy])
                throw IllegalArgumentException("unsupported vendor request")
            },
            start = {},
            release = { events += "release$it" },
            recreate = { events += "create2"; 2 },
        )
        assertFalse(result.accepted)
        assertEquals(2, result.codec)
        assertEquals(listOf("configure1","release1","create2"), events)
        assertFalse(full.keys.any { it.startsWith("vendor.") })
        assertEquals(4, full.size)
    }

    @Test fun acceptedRequestKeepsOriginalCodecWithoutClaimingHardwareEffect() {
        var releases = 0
        var starts = 0
        val result = HisiLowLatencyRequest.configure(1, mapOf(req to 1,rdy to -1), HisiLowLatencyRequest.fullParameters(60),
            configure = { _, params -> assertEquals(-1,params[rdy]) },
            start = { starts++ },
            release = { releases++ }, recreate = { error("must not recreate an accepted codec") })
        assertTrue(result.accepted); assertEquals(1,result.codec); assertEquals(0,releases); assertEquals(1,starts)
    }

    @Test fun startFailureReleasesAndRebuildsBeforeReturningToStandardFallback() {
        val events = mutableListOf<String>()
        val full = HisiLowLatencyRequest.fullParameters(60)
        val result = HisiLowLatencyRequest.configure(1,mapOf(req to 1,rdy to -1),full,
            configure = { id, _ -> events += "configure$id" },
            start = { id -> events += "start$id"; throw IllegalStateException("start rejected") },
            release = { id -> events += "release$id" },
            recreate = { events += "create2"; 2 })
        assertFalse(result.accepted)
        assertEquals(VendorFailure.START,result.failure)
        assertEquals(2,result.codec)
        assertEquals(listOf("configure1","start1","release1","create2"),events)
        assertFalse(full.keys.any { it.startsWith("vendor.") })
    }

    @Test fun releaseFailureStopsInsteadOfReusingDirtyCodecOrRecreating() {
        val events = mutableListOf<String>()
        try {
            HisiLowLatencyRequest.configure(1,mapOf(req to 1), emptyMap(),
                configure = { _, _ -> throw IllegalArgumentException("configure") },
                start = {},
                release = { events += "release"; throw IllegalStateException("release") },
                recreate = { events += "recreate"; 2 })
            fail("cleanup failure must abort")
        } catch (error: IllegalStateException) { assertEquals("release", error.message) }
        assertEquals(listOf("release"),events)
    }

    private data class Gate(val diagnostic: Boolean,val enabled: Boolean,val api: Int,val codec: String,val mime: String)
}
