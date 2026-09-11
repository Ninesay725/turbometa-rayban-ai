package com.smartview.glassai.ui.screens

import org.junit.Assert.*
import org.junit.Test

class AssistantSettingsInputTest {
    @Test fun packageInputAcceptsSeparatorsButRejectsTheEntireInvalidDraft() {
        assertEquals(setOf("com.tencent.mm", "com.example.chat"),
            parseAssistantNotificationPackages(" com.tencent.mm, com.example.chat\ncom.tencent.mm\r\n"))
        assertEquals(emptySet<String>(), parseAssistantNotificationPackages(" , \n"))
        assertNull(parseAssistantNotificationPackages("com.tencent.mm, not an app"))
        assertNull(parseAssistantNotificationPackages("com.tencent.mm\ncom.*"))
    }
}
