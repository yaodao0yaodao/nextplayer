package dev.anilbeesetti.nextplayer.settings.screens.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `semantic version comparison detects newer releases`() {
        assertTrue(isVersionNewer("0.18.0", "0.17.5"))
        assertTrue(isVersionNewer("0.17.6", "0.17.5"))
        assertFalse(isVersionNewer("0.17.5", "0.17.5"))
        assertFalse(isVersionNewer("0.17.4", "0.17.5"))
    }
}
