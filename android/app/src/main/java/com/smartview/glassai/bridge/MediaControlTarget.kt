package com.smartview.glassai.bridge

/** Read every candidate at tap time; a queued callback must not decide the target of a command. */
internal fun <T> resolveMediaTarget(
    candidates: List<T>, allowedPackages: Set<String>, snapshot: (T) -> MediaSnapshot,
): T? {
    val pairs = candidates.map { it to snapshot(it) }
    val selected = selectMedia(pairs.map { it.second }, allowedPackages) ?: return null
    return pairs.first { it.second === selected }.first
}
