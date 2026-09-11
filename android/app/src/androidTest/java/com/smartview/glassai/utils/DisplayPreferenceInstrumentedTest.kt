package com.smartview.glassai.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** No shared credential-store setup/teardown: this fixture owns only its prefixed files. */
@RunWith(AndroidJUnit4::class)
class DisplayPreferenceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun glassesDisplayEnabledPersists() {
        // Use a separate encrypted file: prove the absent-key default without changing the
        // user's actual display preference, and read via newly constructed managers.
        val prefix = "phase_c_${java.util.UUID.randomUUID()}_"
        val files = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val file = prefix + name
                files.add(file)
                return context.getSharedPreferences(file, mode)
            }
        }
        try {
            val first = APIKeyManager(isolated)
            assertEquals(true, first.isGlassesDisplayEnabled())
            first.setGlassesDisplayEnabled(false)
            assertEquals(false, APIKeyManager(isolated).isGlassesDisplayEnabled())
            first.setGlassesDisplayEnabled(true)
            assertEquals(true, APIKeyManager(isolated).isGlassesDisplayEnabled())
        } finally {
            files.forEach { context.deleteSharedPreferences(it) }
        }
    }

}
