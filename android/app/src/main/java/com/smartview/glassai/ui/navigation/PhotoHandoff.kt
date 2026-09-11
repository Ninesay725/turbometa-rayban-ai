package com.smartview.glassai.ui.navigation

/** One RAM-only image addressed to one destination. Unrelated navigation cannot consume it. */
internal class PhotoHandoff<T : Any> {
    private var pending: Pair<String, T>? = null

    fun put(destination: String, image: T) { pending = destination to image }

    fun take(destination: String): T? {
        val value = pending?.takeIf { it.first == destination } ?: return null
        pending = null
        return value.second
    }
}
