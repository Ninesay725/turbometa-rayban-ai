package com.smartview.glassai.glasses

import android.content.Context
import androidx.annotation.StringRes
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import com.smartview.glassai.R

/**
 * Localized (zh/en) messages for every DAT error branch (spec §5.9).
 * The `when`s already list every 0.9.0 constant, so the `else` branches are redundant today
 * (hence the @Suppress); they exist only so a future SDK enum case cannot break compilation.
 * GlassesErrorMessagesTest asserts that no case falls through to dat_error_unknown, so such a
 * case fails the unit test until a string is added.
 */
@Suppress("REDUNDANT_ELSE_IN_WHEN")
object GlassesErrorMessages {

    @StringRes
    fun resId(error: DeviceSessionError): Int = when (error) {
        DeviceSessionError.CAPABILITY_DENIED -> R.string.dat_session_capability_denied
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> R.string.dat_session_no_eligible_device
        DeviceSessionError.SESSION_ALREADY_STOPPED -> R.string.dat_session_already_stopped
        DeviceSessionError.SESSION_IDLE -> R.string.dat_session_idle
        DeviceSessionError.CAPABILITY_ALREADY_ADDED -> R.string.dat_session_capability_already_added
        DeviceSessionError.CAPABILITY_NOT_FOUND -> R.string.dat_session_capability_not_found
        DeviceSessionError.DEVICE_DISCONNECTED -> R.string.dat_session_device_disconnected
        DeviceSessionError.SESSION_ENDED_BY_DEVICE -> R.string.dat_session_ended_by_device
        DeviceSessionError.SESSION_ALREADY_EXISTS -> R.string.dat_session_already_exists
        DeviceSessionError.THERMAL_CRITICAL -> R.string.dat_session_thermal_critical
        DeviceSessionError.THERMAL_EMERGENCY -> R.string.dat_session_thermal_emergency
        DeviceSessionError.PEAK_POWER_SHUTDOWN -> R.string.dat_session_peak_power_shutdown
        DeviceSessionError.BATTERY_CRITICAL -> R.string.dat_session_battery_critical
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> R.string.dat_session_dat_app_update_required
        DeviceSessionError.DWA_UNAVAILABLE -> R.string.dat_session_dwa_unavailable
        DeviceSessionError.UNEXPECTED_ERROR -> R.string.dat_session_unexpected_error
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: StreamError): Int = when (error) {
        StreamError.STREAM_ERROR -> R.string.dat_stream_error
        StreamError.CRITICAL_STREAM_ERROR -> R.string.dat_stream_critical_error
        StreamError.HINGE_CLOSED -> R.string.dat_stream_hinge_closed
        StreamError.PERMISSIONS_DENIED -> R.string.dat_stream_permissions_denied
        StreamError.THERMAL_HOT -> R.string.dat_stream_thermal_hot
        StreamError.BATTERY_LOW -> R.string.dat_stream_battery_low
        StreamError.PEAK_POWER_LIMIT -> R.string.dat_stream_peak_power_limit
        StreamError.TIMEOUT -> R.string.dat_stream_timeout
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: CaptureError): Int = when (error) {
        CaptureError.DeviceDisconnected -> R.string.dat_capture_device_disconnected
        CaptureError.NotStreaming -> R.string.dat_capture_not_streaming
        CaptureError.CaptureInProgress -> R.string.dat_capture_in_progress
        CaptureError.CaptureFailed -> R.string.photo_capture_failed
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: RegistrationError): Int = when (error) {
        RegistrationError.ALREADY_REGISTERED -> R.string.dat_registration_already_registered
        RegistrationError.ALREADY_UNREGISTERED -> R.string.dat_registration_already_unregistered
        RegistrationError.FAILED_TO_REGISTER -> R.string.dat_registration_failed_to_register
        RegistrationError.FAILED_TO_UNREGISTER -> R.string.dat_registration_failed_to_unregister
        RegistrationError.META_AI_NOT_INSTALLED -> R.string.dat_registration_meta_ai_not_installed
        RegistrationError.UNKNOWN -> R.string.dat_registration_unknown
        else -> R.string.dat_error_unknown
    }

    @StringRes
    fun resId(error: CameraError): Int = when (error) {
        is CameraError.CameraBusy -> R.string.glasses_camera_busy
        CameraError.NoSession -> R.string.glasses_no_session
        CameraError.SessionNotStarted -> R.string.glasses_session_timeout
        is CameraError.Sdk -> resId(error.error)
    }

    fun of(context: Context, error: DeviceSessionError): String = context.getString(resId(error))
    fun of(context: Context, error: StreamError): String = context.getString(resId(error))
    fun of(context: Context, error: CaptureError): String = context.getString(resId(error))
    fun of(context: Context, error: RegistrationError): String = context.getString(resId(error))
    fun of(context: Context, error: CameraError): String = context.getString(resId(error))
}
