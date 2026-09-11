package com.smartview.glassai.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-confined short retention of an already-live session; never starts a session itself. */
internal class NotificationDisplayLease(
    private val scope: CoroutineScope,
    private val canShow: () -> Boolean,
    private val acquire: () -> Unit,
    private val release: () -> Unit,
    private val clearOwnedCard: () -> Unit,
    private val dwellMs: Long = 10_000,
) {
    private var held = false
    private var expiry: Job? = null

    fun show(publish: () -> Unit): Boolean {
        if (!canShow()) { retire(); return false }
        expiry?.cancel()
        if (!held) { acquire(); held = true }
        publish()
        expiry = scope.launch { delay(dwellMs); retire() }
        return true
    }

    fun retire() {
        expiry?.cancel(); expiry = null
        if (!held) return
        held = false // Clear may synchronously notify an observer that calls retire again.
        clearOwnedCard()
        release()
    }
}
