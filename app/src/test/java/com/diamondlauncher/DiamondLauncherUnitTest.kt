package com.diamondlauncher

import com.diamondlauncher.core.auth.OfflineAuthManager
import com.diamondlauncher.core.mods.ModAndPackManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiamondLauncherUnitTest {

    @Test
    fun offlineUuidMatchesHorizonRustAndVanilla() {
        assertEquals(
            "b50ad385-829d-3141-a216-7e7d7539ba7f",
            OfflineAuthManager.computeOfflineUuidJvm("Notch", dashed = true)
        )
        assertEquals(
            "b50ad385829d3141a2167e7d7539ba7f",
            OfflineAuthManager.computeOfflineUuidJvm("Notch", dashed = false)
        )
    }

    @Test
    fun offlineUsernameValidationRules() {
        assertTrue(OfflineAuthManager.isValidOfflineName("Notch"))
        assertTrue(OfflineAuthManager.isValidOfflineName("Player_26_3"))
        assertFalse(OfflineAuthManager.isValidOfflineName("ab"))
        assertFalse(OfflineAuthManager.isValidOfflineName("12345678901234567"))
        assertFalse(OfflineAuthManager.isValidOfflineName("bad name"))
    }

    @Test
    fun logScrubberRedactsHomeAndPlayerName() {
        val raw = "Loading /data/user/0/com.diamondlauncher/files for Notch"
        val scrubbed = ModAndPackManager.scrubLog(raw, "/data/user/0/com.diamondlauncher", listOf("Notch"))
        assertFalse(scrubbed.contains("Notch"))
        assertTrue(scrubbed.contains("<player>"))
    }
}