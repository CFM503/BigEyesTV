package com.bigeyes.tv.player

import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.model.Episode
import com.bigeyes.tv.player.model.PlaybackSession
import com.bigeyes.tv.player.model.PlaybackState
import com.bigeyes.tv.player.remote.StatusThrottle
import com.bigeyes.tv.player.remote.TvStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class TvStatusReporterTest {

    private fun episode(index: Int = 0) = Episode(
        seriesId = "s1",
        seriesTitle = "白夜追凶",
        episodeNumber = index + 1,
        episodeIndex = index,
        playUrl = "http://cdn.example.com/e$index.m3u8"
    )

    private fun session(
        state: PlaybackState,
        index: Int = 0,
        position: Long = 30_000L,
        duration: Long = 120_000L
    ) = PlaybackSession(
        seriesId = "s1",
        seriesTitle = "白夜追凶",
        currentEpisode = episode(index),
        currentIndex = index,
        playbackState = state,
        position = position,
        duration = duration,
        autoPlayNext = true
    )

    @Test
    fun testStateMappingMatchesCompanionContract() {
        assertEquals(PlaybackIntentContract.STATE_PLAYING, TvStatusSnapshot.mapState(PlaybackState.PLAYING))
        assertEquals(PlaybackIntentContract.STATE_PLAYING, TvStatusSnapshot.mapState(PlaybackState.BUFFERING))
        assertEquals(PlaybackIntentContract.STATE_PLAYING, TvStatusSnapshot.mapState(PlaybackState.LOADING))
        assertEquals(PlaybackIntentContract.STATE_PAUSED, TvStatusSnapshot.mapState(PlaybackState.PAUSED))
        assertEquals(PlaybackIntentContract.STATE_COMPLETED, TvStatusSnapshot.mapState(PlaybackState.COMPLETED))
        assertEquals(PlaybackIntentContract.STATE_ERROR, TvStatusSnapshot.mapState(PlaybackState.ERROR))
        assertEquals(PlaybackIntentContract.STATE_STOPPED, TvStatusSnapshot.mapState(PlaybackState.STOPPED))
        assertEquals(PlaybackIntentContract.STATE_STOPPED, TvStatusSnapshot.mapState(PlaybackState.IDLE))
    }

    @Test
    fun testSnapshotProjectsSessionFields() {
        val snapshot = TvStatusSnapshot.from(session(PlaybackState.PLAYING, index = 2, position = 45_000L))

        assertEquals(PlaybackIntentContract.STATE_PLAYING, snapshot.state)
        assertEquals("s1", snapshot.seriesId)
        assertEquals("白夜追凶", snapshot.seriesTitle)
        assertEquals(2, snapshot.episodeIndex)
        assertEquals(3, snapshot.episodeNumber)
        assertEquals(45_000L, snapshot.positionMs)
        assertEquals(120_000L, snapshot.durationMs)
        assertTrue(snapshot.autoPlayNext)
    }

    @Test
    fun testSnapshotSignatureIgnoresProgressButTracksStateAndEpisode() {
        val a = TvStatusSnapshot.from(session(PlaybackState.PLAYING, position = 1_000L))
        val b = TvStatusSnapshot.from(session(PlaybackState.PLAYING, position = 60_000L))
        val c = TvStatusSnapshot.from(session(PlaybackState.PAUSED, position = 60_000L))
        val d = TvStatusSnapshot.from(session(PlaybackState.PLAYING, index = 1, position = 0L))

        assertEquals(a.signature, b.signature)
        assertNotEquals(a.signature, c.signature)
        assertNotEquals(a.signature, d.signature)
    }

    @Test
    fun testThrottleAlwaysSendsStateAndEpisodeTransitions() {
        val throttle = StatusThrottle(1_000L)

        assertTrue(throttle.shouldSend("PLAYING|s1|0", now = 0L))
        throttle.markSent("PLAYING|s1|0", now = 0L)

        // Same state, next tick -> coalesced
        assertFalse(throttle.shouldSend("PLAYING|s1|0", now = 500L))
        throttle.markSent("PLAYING|s1|0", now = 500L)

        // Episode transition -> immediate
        assertTrue(throttle.shouldSend("PLAYING|s1|1", now = 600L))
    }

    @Test
    fun testThrottleCoalescesProgressToInterval() {
        val throttle = StatusThrottle(1_000L)

        assertTrue(throttle.shouldSend("PLAYING|s1|0", now = 0L))
        throttle.markSent("PLAYING|s1|0", now = 0L)

        assertFalse(throttle.shouldSend("PLAYING|s1|0", now = 999L))
        assertTrue(throttle.shouldSend("PLAYING|s1|0", now = 1_000L))
    }

    @Test
    fun testThrottleResetClearsHistory() {
        val throttle = StatusThrottle(1_000L)
        throttle.markSent("STOPPED|s1|0", now = 10_000L)
        throttle.reset()

        assertTrue(throttle.shouldSend("STOPPED|s1|0", now = 10_001L))
    }
}
