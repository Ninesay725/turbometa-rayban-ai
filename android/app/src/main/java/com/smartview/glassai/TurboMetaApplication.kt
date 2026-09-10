package com.smartview.glassai

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class TurboMetaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize the DAT SDK once per process, before any Activity, Service or ViewModel
        // touches Wearables APIs (the wake-word QuickVisionService can start without an Activity).
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e(TAG, "DAT SDK initialize failed: ${error.description}")
        }
    }

    companion object {
        private const val TAG = "TurboMetaApplication"

        lateinit var instance: TurboMetaApplication
            private set
    }
}
