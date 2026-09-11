package com.smartview.glassai.utils

/**
 * Splits the pre-2.0 single `rtmp_url` (`rtmp://host/app/streamkey`) into the server URL and the
 * stream key (iOS 2.0 stability fix 4.7: the key moves to encrypted storage and is never shown).
 * Rule: the last path segment is the key only when the path has at least two segments.
 */
object RtmpUrlSplitter {
    fun split(fullUrl: String): Pair<String, String> {
        val trimmed = fullUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "" to ""
        val schemeEnd = trimmed.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = trimmed.indexOf('/', authorityStart)
        if (pathStart < 0) return trimmed to ""
        val segments = trimmed.substring(pathStart + 1).split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return trimmed to ""
        val key = segments.last()
        val server = trimmed.substring(0, trimmed.length - key.length - 1)
        return server to key
    }

    fun join(serverUrl: String, streamKey: String): String {
        val server = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()
        return if (key.isEmpty()) server else "$server/$key"
    }
}
