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
    val isLastEpisode: Boolean = false,
    /**
     * Human readable reason for the current BUFFERING state. Empty/null means the generic
     * default copy should be shown. Populated by the engine so auto-retry progress
     * ("网络不稳定，正在尝试恢复... (1/3)") reaches the UI instead of being dropped.
     */
    val playbackHint: String? = null,
    /** Current automatic network recovery attempt (0 = not retrying). */
    val retryAttempt: Int = 0,
    /** Maximum automatic network recovery attempts for the current stall. */
    val retryMax: Int = 0,
    /** Latched when automatic recovery was exhausted; cleared when playback recovers. */
    val isNetworkInterrupted: Boolean = false
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
