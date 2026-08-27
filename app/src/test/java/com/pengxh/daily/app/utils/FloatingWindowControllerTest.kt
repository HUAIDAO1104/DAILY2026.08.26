package com.pengxh.daily.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowControllerTest {

    @Test
    fun countdownStateSurvivesUntilExplicitlyHidden() {
        try {
            FloatingWindowController.updateTime(30)

            assertTrue(FloatingWindowController.state.value.visible)
            assertEquals(30, FloatingWindowController.state.value.seconds)

            FloatingWindowController.updateTime(12)
            assertTrue(FloatingWindowController.state.value.visible)
            assertEquals(12, FloatingWindowController.state.value.seconds)
        } finally {
            FloatingWindowController.hide()
        }

        assertFalse(FloatingWindowController.state.value.visible)
        assertEquals(0, FloatingWindowController.state.value.seconds)
    }
}
