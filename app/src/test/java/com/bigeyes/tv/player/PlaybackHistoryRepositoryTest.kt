package com.bigeyes.tv.player

import com.bigeyes.tv.player.history.PlaybackHistoryEntry
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryRepositoryTest {

    @Test
    fun testHistoryCompletionThreshold30Seconds() {
        val durationMs = 120_000L // 2 minutes

        // Position at 80s -> 40s remaining -> not completed
        val entryNotCompleted = PlaybackHistoryEntry(
            seriesId = "series_history_1",
            seriesTitle = "测试剧集",
            episodeIndex = 0,
            episodeNumber = 1,
            position = 80_000L,
            duration = durationMs
        )
        assertFalse(entryNotCompleted.isCompleted)

        // Position at 90s -> 30s remaining -> completed threshold reached!
        val entryCompleted30s = PlaybackHistoryEntry(
            seriesId = "series_history_1",
            seriesTitle = "测试剧集",
            episodeIndex = 0,
            episodeNumber = 1,
            position = 90_000L,
            duration = durationMs
        )
        assertTrue(entryCompleted30s.isCompleted)

        // Position at 115s -> 5s remaining -> completed
        val entryCompleted115s = PlaybackHistoryEntry(
            seriesId = "series_history_1",
            seriesTitle = "测试剧集",
            episodeIndex = 0,
            episodeNumber = 1,
            position = 115_000L,
            duration = durationMs
        )
        assertTrue(entryCompleted115s.isCompleted)
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = PlaybackHistoryEntry(
            seriesId = "series_json_test",
            seriesTitle = "庆余年",
            episodeIndex = 4,
            episodeNumber = 5,
            position = 1_234_567L,
            duration = 2_700_000L,
            updatedAt = 1700000000000L
        )

        val json = original.toJson()
        val restored = PlaybackHistoryEntry.fromJson(json)

        assertEquals(original.seriesId, restored.seriesId)
        assertEquals(original.seriesTitle, restored.seriesTitle)
        assertEquals(original.episodeIndex, restored.episodeIndex)
        assertEquals(original.episodeNumber, restored.episodeNumber)
        assertEquals(original.position, restored.position)
        assertEquals(original.duration, restored.duration)
        assertEquals(original.updatedAt, restored.updatedAt)
    }
}
