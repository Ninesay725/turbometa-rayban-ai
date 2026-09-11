package com.smartview.glassai.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Null means unobserved: DAT 0.9 exposes commands, but no getters for these mock states. */
data class MockDeviceFlags(
    val isPoweredOn: Boolean? = null,
    val isDonned: Boolean? = null,
    val isUnfolded: Boolean? = null,
)

/** Process-local optimistic state, matching the lifetime of paired MockDeviceKit devices. */
object MockDeviceState {
    private val mutableFlags = MutableStateFlow<Map<String, MockDeviceFlags>>(emptyMap())
    val flags: StateFlow<Map<String, MockDeviceFlags>> = mutableFlags.asStateFlow()

    fun get(deviceId: String): MockDeviceFlags = flags.value[deviceId] ?: MockDeviceFlags()

    fun update(deviceId: String, transform: (MockDeviceFlags) -> MockDeviceFlags) {
        mutableFlags.update { it + (deviceId to transform(it[deviceId] ?: MockDeviceFlags())) }
    }

    fun remove(deviceId: String) {
        mutableFlags.update { it - deviceId }
    }

    fun clear() {
        mutableFlags.value = emptyMap()
    }
}
