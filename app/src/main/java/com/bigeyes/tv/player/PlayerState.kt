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
}
