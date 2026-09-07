package com.bigeyes.tv.player

import com.bigeyes.tv.player.controller.CompletionGuard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CompletionGuardTest {

    private lateinit var guard: CompletionGuard

    @Before
    fun setUp() {
        guard = CompletionGuard()
    }

    @Test
    fun testGenerationIdMonotonicallyIncreases() {
        val gen1 = guard.nextGeneration()
        val gen2 = guard.nextGeneration()
        val gen3 = guard.nextGeneration()

        assertTrue(gen2 > gen1)
        assertTrue(gen3 > gen2)
        assertEquals(gen3, guard.getCurrentGenerationId())
    }

    @Test
    fun testDuplicateCompletionRejectedForSameGeneration() {
        val genId = guard.nextGeneration()
        val episodeKey = "http://example.com/ep1.m3u8"

        // First completion must pass
        val firstResult = guard.canComplete(genId, episodeKey)
        assertTrue("First completion callback should be accepted", firstResult)

        // Immediate duplicate completion for the same generation must be rejected
        val duplicateResult = guard.canComplete(genId, episodeKey)
        assertFalse("Duplicate completion callback must be rejected", duplicateResult)

        // Third duplicate must also be rejected
        val thirdResult = guard.canComplete(genId, episodeKey)
        assertFalse("Third duplicate completion must be rejected", thirdResult)
    }

    @Test
    fun testObsoleteGenerationRejected() {
        val gen1 = guard.nextGeneration()
        val gen2 = guard.nextGeneration() // Now current is gen2

        // Calling completion with stale gen1 must be rejected
        val obsoleteResult = guard.canComplete(gen1, "http://example.com/ep1.m3u8")
        assertFalse("Obsolete generation callback must be rejected", obsoleteResult)

        // Calling with valid current gen2 must succeed
        val validResult = guard.canComplete(gen2, "http://example.com/ep2.m3u8")
        assertTrue("Current generation callback must succeed", validResult)
    }

    @Test
    fun testResetClearsCompletionState() {
        val genId = guard.nextGeneration()
        val episodeKey = "http://example.com/ep1.m3u8"

        assertTrue(guard.canComplete(genId, episodeKey))
        assertFalse(guard.canComplete(genId, episodeKey))

        guard.reset()

        // After reset, current generation can complete again if required
        assertTrue(guard.canComplete(genId, "http://example.com/ep1_new.m3u8"))
    }
}
