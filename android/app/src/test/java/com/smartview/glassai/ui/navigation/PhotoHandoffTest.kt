package com.smartview.glassai.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class PhotoHandoffTest {
    @Test fun unrelatedNutritionNavigationDoesNotReceiveTheVisionPhoto() {
        val handoff = PhotoHandoff<Any>()
        val image = Any()
        handoff.put("vision", image)
        assertNull(handoff.take("lean_eat"))
        assertSame(image, handoff.take("vision"))
        assertNull(handoff.take("vision"))
        assertNull(handoff.take("lean_eat"))
    }

    @Test fun explicitNutritionPhotoIsConsumedExactlyOnce() {
        val handoff = PhotoHandoff<Any>()
        val image = Any()
        handoff.put("lean_eat", image)
        assertSame(image, handoff.take("lean_eat"))
        assertNull(handoff.take("lean_eat"))
    }

    @Test fun newerExplicitPhotoReplacesTheUnconsumedOne() {
        val handoff = PhotoHandoff<Any>()
        val newer = Any()
        handoff.put("vision", Any())
        handoff.put("lean_eat", newer)
        assertNull(handoff.take("vision"))
        assertSame(newer, handoff.take("lean_eat"))
    }
}
