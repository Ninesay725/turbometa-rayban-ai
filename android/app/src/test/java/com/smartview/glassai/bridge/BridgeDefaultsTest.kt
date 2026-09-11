package com.smartview.glassai.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeDefaultsTest {
    private val oldDefaults = setOf("com.netease.cloudmusic", "com.luna.music")
    private val newDefaults = oldDefaults + "com.tencent.qqmusic"

    @Test fun onlyUnsetOrUncustomizedLegacyDefaultsGainQQMusic() {
        assertEquals(newDefaults, migrateMediaPackages(null, false))
        assertEquals(newDefaults, migrateMediaPackages(oldDefaults, false))
        assertEquals(oldDefaults, migrateMediaPackages(oldDefaults, true))
        listOf(emptySet(), setOf("com.netease.cloudmusic"), setOf("custom.player"),
            oldDefaults + "custom.player").forEach { custom ->
            assertEquals(custom, migrateMediaPackages(custom, false))
        }
        assertEquals(newDefaults, migrateMediaPackages(newDefaults, false))
    }
}
