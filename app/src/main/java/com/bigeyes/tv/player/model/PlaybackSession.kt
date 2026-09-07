package com.bigeyes.tv.player.model

import java.io.Serializable

/**
 * High-level Playback State for TV Player Session.
 */
enum class PlaybackState {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
    BUFFERING,
    COMPLETED,
    ERROR,
    STOPPED
}

/**
 * Immutable Playback Session state holder observed by UI and services via StateFlow.
 */
data class PlaybackSession(
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val currentEpisode: Episode? = null,
    val currentIndex: Int = 0,
    val autoPlayNext: Boolean = true,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val position: Long = 0L,
    val duration: Long = 0L,
    val speed: Float = 1.0f,
    val countdownRemainingSeconds: Int? = null,
    val errorMessage: String? = null,
    val isLastEpisode: Boolean = false
) : Serializable {

    val isPlaying: Boolean
        get() = playbackState == PlaybackState.PLAYING

    val isPaused: Boolean
        get() = playbackState == PlaybackState.PAUSED

    val isBuffering: Boolean
        get() = playbackState == PlaybackState.BUFFERING

    val isLoading: Boolean
        get() = playbackState == PlaybackState.LOADING

    val isCompleted: Boolean
        get() = playbackState == PlaybackState.COMPLETED

    val isError: Boolean
        get() = playbackState == PlaybackState.ERROR

    val isIdle: Boolean
        get() = playbackState == PlaybackState.IDLE || playbackState == PlaybackState.STOPPED

    val hasCountdown: Boolean
        get() = countdownRemainingSeconds != null && countdownRemainingSeconds > 0

    companion object {
        private const val serialVersionUID = 1L

        val INITIAL = PlaybackSession()
    }
}
