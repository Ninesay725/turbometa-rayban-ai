package com.smartview.glassai.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.services.openclaw.OpenClawDeviceIdentity
import com.smartview.glassai.services.openclaw.OpenClawDeviceIdentityStore
import com.smartview.glassai.services.openclaw.SecureOpenClawSettingsStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class APIKeyManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var manager: APIKeyManager
    private lateinit var store: SecureOpenClawSettingsStore

    private var savedUrl: String? = null
    private var savedKey: String? = null
    private var savedToken: String? = null
    private var savedScheme = "ws"
    private var savedPort = 0
    private var savedSeed: ByteArray? = null

    @Before
    fun setUp() {
        manager = APIKeyManager.getInstance(context)
        store = SecureOpenClawSettingsStore(context)
        savedUrl = manager.getRtmpUrl()
        savedKey = manager.getRtmpStreamKey()
        savedToken = store.loadToken()
        savedScheme = store.scheme
        savedPort = store.port
        savedSeed = store.loadDeviceSeed()
    }

    @After
    fun tearDown() {
        manager.saveRtmpUrl(savedUrl ?: "")
        savedKey?.let { manager.saveRtmpStreamKey(it) } ?: manager.deleteRtmpStreamKey()
        store.saveToken(savedToken)
        store.scheme = savedScheme
        store.port = savedPort
        // Never leave the deterministic test seed behind as the app identity
        store.saveDeviceSeed(savedSeed ?: OpenClawDeviceIdentity.generate().seedCopy())
    }

    @Test
    fun legacyRtmpUrlIsSplitIntoServerAndStreamKeyOnce() {
        manager.saveRtmpUrl("rtmp://h/live/key")
        manager.deleteRtmpStreamKey()

        manager.rerunRtmpMigrationForTests() // what the first 2.0.0 launch does in init {}

        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertEquals("key", manager.getRtmpStreamKey())

        // An already-split pair is left alone on later runs (the key is present)
        manager.rerunRtmpMigrationForTests()
        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertEquals("key", manager.getRtmpStreamKey())
    }

    @Test
    fun rtmpUrlWithoutAKeySegmentMigratesToNoKey() {
        manager.saveRtmpUrl("rtmp://h/live")
        manager.deleteRtmpStreamKey()

        manager.rerunRtmpMigrationForTests()

        assertEquals("rtmp://h/live", manager.getRtmpUrl())
        assertNull(manager.getRtmpStreamKey())
    }

    @Test
    fun openClawTokenSchemeAndPortAreNormalizedInTheEncryptedStore() {
        store.saveToken("  tok  ")
        assertEquals("tok", store.loadToken())
        store.saveToken("   ")
        assertNull(store.loadToken()) // blank deletes (iOS quirk not ported)
        store.scheme = "bogus"
        assertEquals("ws", store.scheme)
        store.scheme = "wss"
        assertEquals("wss", store.scheme)
        store.port = 70_000
        assertEquals(18789, store.port) // out of range -> default
    }

    @Test
    fun deviceSeedPersistsAndReproducesTheIdentity() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        store.saveDeviceSeed(seed)
        assertArrayEquals(seed, store.loadDeviceSeed())
        assertEquals(
            OpenClawDeviceIdentity.fromSeed(seed).deviceId,
            OpenClawDeviceIdentityStore.loadOrCreate(store).deviceId,
        )
    }
}
