package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Wraps an SDK Camera (and its Stream) behind [GlassesCamera]. */
class SdkGlassesCamera(private val camera: Camera) : GlassesCamera {
    override val streamState: StateFlow<DatStreamState>
        get() = camera.stream.state
    override val videoFrames: Flow<VideoFrame>
        get() = camera.stream.videoStream
    override val streamErrors: Flow<StreamError>
        get() = camera.stream.errorStream

    override fun startStream(): StreamError? =
        camera.stream.start().fold(
            onSuccess = { null },
            onFailure = { error, _ -> error },
        )

    override suspend fun capturePhoto(): PhotoCaptureResult =
        camera.stream.capturePhoto().fold(
            onSuccess = { PhotoCaptureResult.Success(it) },
            onFailure = { error, _ -> PhotoCaptureResult.Failure(error) },
        )

    override fun stop() {
        camera.stop()
    }
}

/** Wraps an SDK DeviceSession behind [GlassesSession]. */
class SdkGlassesSession(private val session: DeviceSession) : GlassesSession {
    override val state: StateFlow<DeviceSessionState>
        get() = session.state
    override val errors: SharedFlow<DeviceSessionError>
        get() = session.errors
    override val nativeSession: DeviceSession
        get() = session

    override fun start() = session.start()

    override fun stop() = session.stop()

    override fun addCamera(config: StreamConfiguration): CameraAddResult =
        session.addCamera(config).fold(
            onSuccess = { CameraAddResult.Success(SdkGlassesCamera(it)) },
            onFailure = { error, _ -> CameraAddResult.Failure(error) },
        )
}

/**
 * Real DAT gateway. Owns the single AutoDeviceSelector (spec §3 decision 7: no filter).
 * Requires Wearables.initialize() to have run (TurboMetaApplication.onCreate()).
 */
class WearablesDatAdapter(
    private val deviceSelector: DeviceSelector = AutoDeviceSelector(),
) : DatSessionFactory, DatDeviceObserver {

    override fun createSession(): SessionCreateResult =
        Wearables.createSession(deviceSelector).fold(
            onSuccess = { SessionCreateResult.Success(SdkGlassesSession(it)) },
            onFailure = { error, _ -> SessionCreateResult.Failure(error) },
        )

    /**
     * Re-evaluates devicesMetadata on every Wearables.devices change (like the 0.9.0 sample), so a
     * metadata entry that appears after the selector already emitted the id is picked up instead of
     * the flow settling on unknownDevice(id).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> =
        deviceSelector.activeDeviceFlow()
            // takeIf: on unpair/Bluetooth-off the selector can still hold an id whose metadata
            // entry has already been removed. Resolving it to null shows "no device" instead of a
            // transient "<raw id> (UNKNOWN)" card.
            .combine(Wearables.devices) { id, devices -> id?.takeIf { it in devices } }
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    val metadata = Wearables.devicesMetadata[id]
                    if (metadata == null) {
                        flowOf(unknownDevice(id))
                    } else {
                        metadata.map { device -> device.toGlassesDeviceInfo(id) }
                    }
                }
            }

    private fun unknownDevice(id: DeviceIdentifier) = GlassesDeviceInfo(
        id = id.toString(),
        name = id.toString(),
        deviceType = DeviceType.UNKNOWN,
        isDisplayCapable = false,
        compatibility = DeviceCompatibility.UNDEFINED,
    )

    private fun Device.toGlassesDeviceInfo(id: DeviceIdentifier) = GlassesDeviceInfo(
        id = id.toString(),
        name = name.ifEmpty { id.toString() },
        deviceType = deviceType,
        isDisplayCapable = isDisplayCapable(),
        compatibility = compatibility,
    )
}
