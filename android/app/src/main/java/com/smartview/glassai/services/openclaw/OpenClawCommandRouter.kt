package com.smartview.glassai.services.openclaw

import android.os.Build
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.smartview.glassai.BuildConfig
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.SnapshotResult
import java.util.Base64

/**
 * What OpenClawNodeService needs from a router: one suspend call per `node.invoke`.
 * OpenClawCommandRouter (Task 4) is the production implementation; tests use a fake.
 */
interface OpenClawCommandHandler {
    suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult
}

/** Facts for `device.info`. Built once from BuildConfig/Build; tests pass literals. */
data class OpenClawDeviceInfoSource(
    val appVersion: String,
    val sdkVersion: String,
    val osVersion: String,
) {
    companion object {
        fun fromBuild(): OpenClawDeviceInfoSource = OpenClawDeviceInfoSource(
            appVersion = BuildConfig.VERSION_NAME,
            sdkVersion = BuildConfig.MWDAT_VERSION,
            osVersion = Build.VERSION.RELEASE ?: "unknown",
        )
    }
}

/**
 * Handles the four node commands the app advertises (research §3), using [frames] for the glasses
 * and [deviceInfo] for build facts. Result payload shapes are identical to iOS.
 */
class OpenClawCommandRouter(
    private val frames: GlassesFrameProvider,
    private val deviceInfo: OpenClawDeviceInfoSource,
    private val snapTimeoutMs: Long = DEFAULT_SNAP_TIMEOUT_MS,
) : OpenClawCommandHandler {

    companion object {
        private const val TAG = "OpenClawCommandRouter"
        /** iOS polls isStreaming for up to 5 s before giving up with NO_FRAME. */
        const val DEFAULT_SNAP_TIMEOUT_MS = 5_000L
    }

    override suspend fun handleCommand(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        Log.d(TAG, "command ${request.command} (${request.id})")
        return when (request.command) {
            "camera.snap" -> snap(request)
            "camera.list" -> cameraList(request)
            "device.status" -> deviceStatus(request)
            "device.info" -> deviceInfo(request)
            else -> OpenClawNodeInvokeResult.failure(
                request.id, OpenClawProtocol.ERROR_UNKNOWN_COMMAND, "Unknown command: ${request.command}",
            )
        }
    }

    private suspend fun snap(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        val params = CameraSnapParams.from(request.params)
        val quality = params.quality.coerceIn(0.1, 1.0)
        return when (val result = frames.snapshot(params.maxWidth, quality, snapTimeoutMs)) {
            is SnapshotResult.Ok -> {
                val payload = JsonObject().apply {
                    addProperty("format", "jpg")
                    addProperty("base64", Base64.getEncoder().encodeToString(result.frame.jpeg))
                    addProperty("width", result.frame.width)
                    addProperty("height", result.frame.height)
                }
                OpenClawNodeInvokeResult.success(request.id, payload)
            }
            SnapshotResult.NoFrame ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NO_FRAME, "No video frame available")
            // Codes AND messages are the iOS ones (research §2.9); the Android-specific detail is
            // logged so gateway-side prompts see exactly what they see from the iOS node.
            is SnapshotResult.NotReady -> {
                Log.w(TAG, "camera.snap NOT_READY: ${result.detail}")
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_NOT_READY, "Stream not initialized")
            }
            is SnapshotResult.StreamFailed -> {
                Log.w(TAG, "camera.snap STREAM_FAILED: ${result.detail}")
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_STREAM_FAILED, "Could not start camera stream")
            }
            SnapshotResult.PermissionRequired ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_PERMISSION_REQUIRED, "Glasses camera permission not granted; open the app to grant it")
            SnapshotResult.EncodeFailed ->
                OpenClawNodeInvokeResult.failure(request.id, OpenClawProtocol.ERROR_ENCODE_FAILED, "Failed to encode JPEG")
        }
    }

    private fun cameraList(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult {
        val cameras = JsonArray()
        if (frames.hasActiveDevice) {
            cameras.add(JsonObject().apply {
                addProperty("id", "rayban-main")
                addProperty("name", "Ray-Ban Meta Camera")
                addProperty("facing", "front")
                addProperty("available", true)
            })
        }
        return OpenClawNodeInvokeResult.success(request.id, JsonObject().apply { add("cameras", cameras) })
    }

    private fun deviceStatus(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
        OpenClawNodeInvokeResult.success(request.id, JsonObject().apply {
            addProperty("deviceConnected", frames.hasActiveDevice)
            addProperty("isStreaming", frames.isStreaming)
            addProperty("streamStatus", frames.streamStatus)
            addProperty("hasVideoFrame", frames.hasFrame)
        })

    private fun deviceInfo(request: OpenClawNodeInvokeRequest): OpenClawNodeInvokeResult =
        OpenClawNodeInvokeResult.success(request.id, JsonObject().apply {
            addProperty("deviceType", "Ray-Ban Meta")
            addProperty("appName", "TurboMeta")
            addProperty("appVersion", deviceInfo.appVersion)
            addProperty("sdkVersion", deviceInfo.sdkVersion)
            addProperty("platform", "Android")
            addProperty("osVersion", deviceInfo.osVersion)
        })
}
