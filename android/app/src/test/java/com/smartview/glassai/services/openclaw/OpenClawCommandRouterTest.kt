package com.smartview.glassai.services.openclaw

import com.google.gson.JsonParser
import com.smartview.glassai.glasses.FrameSnapshot
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.SnapshotResult
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawCommandRouterTest {

    private class FakeFrameProvider : GlassesFrameProvider {
        override var hasActiveDevice = true
        override var streamStatus = "stopped"
        override var hasFrame = false
        override val isStreaming: Boolean get() = streamStatus != "stopped"
        var result: SnapshotResult = SnapshotResult.NoFrame
        var lastMaxWidth = -1
        var lastQuality = -1.0
        var lastTimeout = -1L
        override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult {
            lastMaxWidth = maxWidth
            lastQuality = quality
            lastTimeout = timeoutMs
            return result
        }
    }

    private val frames = FakeFrameProvider()
    private val info = OpenClawDeviceInfoSource(appVersion = "2.0.0", sdkVersion = "0.9.0", osVersion = "12")
    private val router = OpenClawCommandRouter(frames, info, snapTimeoutMs = 1234L)

    private fun request(command: String, params: String? = null) = OpenClawNodeInvokeRequest(
        id = "inv-1",
        command = command,
        params = params?.let { JsonParser.parseString(it).asJsonObject },
        timeoutMs = null,
    )

    @Test
    fun snapReturnsJpegBase64WithDimensions() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        frames.result = SnapshotResult.Ok(FrameSnapshot(jpeg, 640, 480))

        val result = router.handleCommand(request("camera.snap", """{"maxWidth": 800, "quality": 0.5}"""))

        assertTrue(result.ok)
        assertEquals("inv-1", result.id)
        val payload = result.payload!!
        assertEquals("jpg", payload.get("format").asString)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), payload.get("base64").asString)
        assertEquals(640, payload.get("width").asInt)
        assertEquals(480, payload.get("height").asInt)
        assertEquals(800, frames.lastMaxWidth)
        assertEquals(0.5, frames.lastQuality, 0.0001)
        assertEquals(1234L, frames.lastTimeout)
    }

    @Test
    fun snapUsesDefaultsAndClampsQuality() = runTest {
        frames.result = SnapshotResult.Ok(FrameSnapshot(byteArrayOf(1), 1, 1))
        router.handleCommand(request("camera.snap", """{"quality": 7}"""))
        assertEquals(1600, frames.lastMaxWidth)
        assertEquals(1.0, frames.lastQuality, 0.0001)
        router.handleCommand(request("camera.snap", """{"quality": 0}"""))
        assertEquals(0.1, frames.lastQuality, 0.0001)
    }

    @Test
    fun snapErrorsMapToTheIosCodes() = runTest {
        frames.result = SnapshotResult.NoFrame
        assertEquals("NO_FRAME", router.handleCommand(request("camera.snap")).error!!.code)
        frames.result = SnapshotResult.NotReady("background")
        val notReady = router.handleCommand(request("camera.snap"))
        assertEquals("NOT_READY", notReady.error!!.code)
        assertEquals("Stream not initialized", notReady.error!!.message) // iOS text; the detail goes to logcat
        frames.result = SnapshotResult.StreamFailed("x")
        val streamFailed = router.handleCommand(request("camera.snap"))
        assertEquals("STREAM_FAILED", streamFailed.error!!.code)
        assertEquals("Could not start camera stream", streamFailed.error!!.message)
        frames.result = SnapshotResult.PermissionRequired
        assertEquals("PERMISSION_REQUIRED", router.handleCommand(request("camera.snap")).error!!.code)
        frames.result = SnapshotResult.EncodeFailed
        val encode = router.handleCommand(request("camera.snap"))
        assertEquals("ENCODE_FAILED", encode.error!!.code)
        assertEquals("Failed to encode JPEG", encode.error!!.message)
        assertFalse(encode.ok)
        assertNull(encode.payload)
    }

    @Test
    fun cameraListDependsOnActiveDevice() = runTest {
        frames.hasActiveDevice = true
        val withDevice = router.handleCommand(request("camera.list")).payload!!.getAsJsonArray("cameras")
        val camera = withDevice.single().asJsonObject
        assertEquals("rayban-main", camera.get("id").asString)
        assertEquals("Ray-Ban Meta Camera", camera.get("name").asString)
        assertEquals("front", camera.get("facing").asString)
        assertTrue(camera.get("available").asBoolean)

        frames.hasActiveDevice = false
        assertEquals(0, router.handleCommand(request("camera.list")).payload!!.getAsJsonArray("cameras").size())
    }

    @Test
    fun deviceStatusReportsTheProviderFlags() = runTest {
        frames.hasActiveDevice = true
        frames.streamStatus = "streaming"
        frames.hasFrame = true
        val payload = router.handleCommand(request("device.status")).payload!!
        assertTrue(payload.get("deviceConnected").asBoolean)
        assertTrue(payload.get("isStreaming").asBoolean)
        assertEquals("streaming", payload.get("streamStatus").asString)
        assertTrue(payload.get("hasVideoFrame").asBoolean)
    }

    @Test
    fun deviceInfoUsesBuildFacts() = runTest {
        val payload = router.handleCommand(request("device.info")).payload!!
        assertEquals("Ray-Ban Meta", payload.get("deviceType").asString)
        assertEquals("TurboMeta", payload.get("appName").asString)
        assertEquals("2.0.0", payload.get("appVersion").asString)
        assertEquals("0.9.0", payload.get("sdkVersion").asString)
        assertEquals("Android", payload.get("platform").asString)
        assertEquals("12", payload.get("osVersion").asString)
    }

    @Test
    fun unknownCommandIsRejected() = runTest {
        val result = router.handleCommand(request("camera.clip"))
        assertFalse(result.ok)
        assertEquals("UNKNOWN_COMMAND", result.error!!.code)
        assertEquals("Unknown command: camera.clip", result.error!!.message)
    }
}
