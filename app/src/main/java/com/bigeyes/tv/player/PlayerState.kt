package com.bigeyes.tv.player

enum class PlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

interface TvPlayerListener {
    fun onStateChanged(state: PlayerState)
    fun onPlaybackStarted(url: String)
    fun onPlaybackStopped()
    fun onError(error: String)

    /**
     * Called when buffering state changes.
     * @param isBuffering true if buffering started, false if completed
     * @param message optional message to display (e.g., "正在缓冲...", "网络不稳定")
     */
    fun onBufferingStateChanged(isBuffering: Boolean, message: String) {}

    /**
     * Called when network retry is attempted.
     * @param attempt current attempt number
     * @param maxAttempts maximum retry attempts
     */
    fun onNetworkRetry(attempt: Int, maxAttempts: Int) {}

    /**
     * Called when network is interrupted and auto-retries exhausted.
     * @param lastPositionMs the last known playback position before interruption
     */
    fun onNetworkInterrupted(lastPositionMs: Long) {}
}
