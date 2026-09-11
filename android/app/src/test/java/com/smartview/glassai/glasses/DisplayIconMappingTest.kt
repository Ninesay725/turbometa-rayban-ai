package com.smartview.glassai.glasses

import com.meta.wearable.dat.display.views.IconName
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayIconMappingTest {
    @Test
    fun everyDisplayIconMapsToAnIconNameOfTheSameName() {
        DisplayIcon.entries.forEach { assertEquals(it.name, it.toIconName().name) }
    }

    @Test
    fun mappingIsInjective() {
        assertEquals(DisplayIcon.entries.size, DisplayIcon.entries.map { it.toIconName() }.toSet().size)
    }

    @Test
    fun iconCatalogStillContainsEveryMappedName() {
        DisplayIcon.entries.forEach { assertEquals(IconName.valueOf(it.name), it.toIconName()) }
    }
}
