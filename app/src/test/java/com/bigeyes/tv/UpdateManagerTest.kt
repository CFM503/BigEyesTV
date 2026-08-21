package com.bigeyes.tv

import com.bigeyes.tv.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testCleanVersionTag() {
        assertEquals("1.0.1", UpdateManager.cleanVersionTag("v1.0.1"))
        assertEquals("1.0.1", UpdateManager.cleanVersionTag("V1.0.1"))
        assertEquals("1.0.1", UpdateManager.cleanVersionTag("1.0.1"))
        assertEquals("1.0.1", UpdateManager.cleanVersionTag("bigeyes-tv-v1.0.1"))
        assertEquals("1.0.2", UpdateManager.cleanVersionTag("bigeyes-tv-1.0.2"))
    }

    @Test
    fun testIsNewerVersion() {
        // Newer versions
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "1.0.2"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "1.1.0"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "2.0.0"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "v1.0.2"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "bigeyes-tv-v1.0.2"))
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "bigeyes-tv-v1.1.0"))

        // Same version
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "1.0.1"))
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "v1.0.1"))
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "bigeyes-tv-v1.0.1"))

        // Older version
        assertFalse(UpdateManager.isNewerVersion("1.0.2", "1.0.1"))
        assertFalse(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.0.9"))
    }

    @Test
    fun testGetCandidateDownloadUrls() {
        val originalUrl = "https://github.com/CFM503/BigEyesTV/releases/download/v1.0.7/BigEyesTV.apk"
        val candidates = UpdateManager.getCandidateDownloadUrls(originalUrl)

        // Should contain mirrors first and originalUrl as last fallback
        assertTrue(candidates.size >= 2)
        assertEquals("https://ghfast.top/$originalUrl", candidates[0])
        assertEquals("https://ghproxy.net/$originalUrl", candidates[1])
        assertEquals(originalUrl, candidates.last())

        // Blank input returns empty list
        assertTrue(UpdateManager.getCandidateDownloadUrls("").isEmpty())
    }
}
