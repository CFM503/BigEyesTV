package com.bigeyes.tv.player.command

import com.bigeyes.tv.player.model.Episode

/**
 * Unified command hierarchy representing all user, protocol, and external playback instructions.
 * All inputs (Remote, Intent, DLNA, AirPlay, UI) dispatch through these commands to ensure
 * single-point-of-truth state transitions.
 */
sealed class PlaybackCommand {
    data class Play(
        val url: String? = null,
        val startPositionMs: Long = 0L,
        val title: String? = null
    ) : PlaybackCommand()
    object Pause : PlaybackCommand()
    object Resume : PlaybackCommand()
    object TogglePlayPause : PlaybackCommand()
    object Stop : PlaybackCommand()
    object Next : PlaybackCommand()
    object Previous : PlaybackCommand()
    data class Seek(val positionMs: Long) : PlaybackCommand()
    data class SeekForward(val stepMs: Long = 15000L) : PlaybackCommand()
    data class SeekBackward(val stepMs: Long = 15000L) : PlaybackCommand()
    data class SetSpeed(val speed: Float) : PlaybackCommand()
    data class PlayEpisode(val index: Int, val startPositionMs: Long = 0L) : PlaybackCommand()
    data class PlayQueue(
        val queue: List<Episode>,
        val startIndex: Int = 0,
        val startPositionMs: Long = 0L,
        val autoPlayNext: Boolean = true
    ) : PlaybackCommand()
    object CancelCountdown : PlaybackCommand()
    data class SetAutoPlayNext(val enabled: Boolean) : PlaybackCommand()
    object Retry : PlaybackCommand()
}
