package com.smartview.glassai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpUrlSplitterTest {

    @Test
    fun splitsAppAndKey() {
        assertEquals("rtmp://a.example.com/live" to "abc-123", RtmpUrlSplitter.split("rtmp://a.example.com/live/abc-123"))
        assertEquals("rtmp://localhost/live" to "stream", RtmpUrlSplitter.split("rtmp://localhost/live/stream"))
        assertEquals("rtmps://live.x.com:443/app/sub" to "k", RtmpUrlSplitter.split("rtmps://live.x.com:443/app/sub/k"))
    }

    @Test
    fun singleSegmentOrNoPathHasNoKey() {
        assertEquals("rtmp://a.example.com/live" to "", RtmpUrlSplitter.split("rtmp://a.example.com/live"))
        assertEquals("rtmp://a.example.com" to "", RtmpUrlSplitter.split("rtmp://a.example.com/"))
        assertEquals("rtmp://a.example.com" to "", RtmpUrlSplitter.split("rtmp://a.example.com"))
        assertEquals("" to "", RtmpUrlSplitter.split("   "))
    }

    @Test
    fun joinRoundTrips() {
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join("rtmp://a.example.com/live", "abc"))
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join("rtmp://a.example.com/live/", " abc "))
        assertEquals("rtmp://a.example.com/live", RtmpUrlSplitter.join("rtmp://a.example.com/live", ""))
        val (server, key) = RtmpUrlSplitter.split("rtmp://a.example.com/live/abc")
        assertEquals("rtmp://a.example.com/live/abc", RtmpUrlSplitter.join(server, key))
    }
}
