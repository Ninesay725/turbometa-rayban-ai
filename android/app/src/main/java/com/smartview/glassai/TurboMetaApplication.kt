package com.smartview.glassai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.services.openclaw.OpenClawIntegration

class TurboMetaApplication : Application() {

    @Volatile
    private var startedActivities = 0

    /** True while at least one Activity is started (spec §1: no background camera.snap). */
    val isInForeground: Boolean
        get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize the DAT SDK once per process, before any Activity, Service or ViewModel
        // touches Wearables APIs (the wake-word QuickVisionService can start without an Activity).
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e(TAG, "DAT SDK initialize failed: ${error.description}")
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // OpenClaw node commands (camera.snap etc.) route through the shared glasses session.
        // install() starts the glasses device observer so the first camera.list / device.status
        // after process start does not report "no glasses" while activeDevice is still filling; the
        // EncryptedSharedPreferences store and the Ed25519 seed still wait for the first connect().
        // install() itself never throws.
        OpenClawIntegration.install(this)
        GlassesDisplayIntegration.install(this)
    }

    companion object {
        private const val TAG = "TurboMetaApplication"

        lateinit var instance: TurboMetaApplication
            private set
    }
}
