package com.smartview.glassai.glasses

import android.app.Activity
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRegistrationGateway : DatRegistrationGateway {
    val state = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val errors = MutableSharedFlow<RegistrationError>(extraBufferCapacity = 4)
    val deviceSet = MutableStateFlow<Set<DeviceIdentifier>>(emptySet())
    var registrationCalls = 0
    var unregistrationCalls = 0
    var firmwareUpdateResult: String? = null
    var datAppUpdateResult: String? = null
    var permission: CameraPermissionCheck = CameraPermissionCheck.Granted
    val calls = mutableListOf<String>()

    override val registrationState: Flow<RegistrationState> = state
    override val registrationErrors: Flow<RegistrationError> = errors
    override val devices: Flow<Set<DeviceIdentifier>> = deviceSet

    override fun startRegistration(activity: Activity) {
        registrationCalls++
        calls += "startRegistration"
    }

    override fun startUnregistration(activity: Activity) {
        unregistrationCalls++
        calls += "startUnregistration"
    }

    override fun openFirmwareUpdate(activity: Activity): String? = firmwareUpdateResult

    override fun openDATGlassesAppUpdate(activity: Activity): String? = datAppUpdateResult

    override suspend fun checkCameraPermission(): CameraPermissionCheck = permission
}
