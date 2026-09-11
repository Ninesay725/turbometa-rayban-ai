package com.smartview.glassai.services.openclaw

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawModelsTest {

    @Test
    fun snapParamsDefaultsMatchIos() {
        val params = CameraSnapParams.from(null)
        assertEquals(1600, params.maxWidth)
        assertEquals(0.8, params.quality, 0.0001)
        assertEquals("jpg", params.format)
    }

    @Test
    fun snapParamsReadTheProvidedFields() {
        val json = JsonParser.parseString("""{"maxWidth": 800, "quality": 0.5, "format": "jpeg"}""").asJsonObject
        val params = CameraSnapParams.from(json)
        assertEquals(800, params.maxWidth)
        assertEquals(0.5, params.quality, 0.0001)
        assertEquals("jpeg", params.format)
    }

    @Test
    fun snapParamsIgnoreMalformedFields() {
        val json = JsonParser.parseString("""{"maxWidth": "wide", "quality": null}""").asJsonObject
        val params = CameraSnapParams.from(json)
        assertEquals(1600, params.maxWidth)
        assertEquals(0.8, params.quality, 0.0001)
    }

    @Test
    fun protocolConstantsAreTheAndroidOnes() {
        assertEquals(4, OpenClawProtocol.PROTOCOL_VERSION)
        assertEquals("openclaw-android", OpenClawProtocol.CLIENT_ID)
        assertEquals("android", OpenClawProtocol.PLATFORM)
        assertEquals("Ray-Ban Meta Glasses", OpenClawProtocol.DISPLAY_NAME)
        assertEquals("node", OpenClawProtocol.CLIENT_MODE)
        assertEquals("node", OpenClawProtocol.ROLE)
        assertEquals(emptyList<String>(), OpenClawProtocol.SCOPES)
        assertEquals(listOf("camera"), OpenClawProtocol.CAPS)
        assertEquals(listOf("camera.snap", "camera.list", "device.status", "device.info"), OpenClawProtocol.COMMANDS)
        assertEquals("turbometa-chat", OpenClawProtocol.SESSION_KEY)
        assertEquals(18789, OpenClawProtocol.DEFAULT_PORT)
        assertEquals("127.0.0.1", OpenClawProtocol.DEFAULT_HOST)
    }
}
