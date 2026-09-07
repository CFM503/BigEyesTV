package com.bigeyes.tv.player.engine

import androidx.media3.ui.PlayerView
import com.bigeyes.tv.player.model.PlaybackState

/**
 * Listener interface for low-level media player engine events.
 */
interface PlayerEngineListener {
    fun onEngineStateChanged(state: PlaybackState)
    fun onPlaybackCompleted()
    fun onPlaybackStarted(url: String)
    fun onError(error: String)
    fun onBufferingStateChanged(isBuffering: Boolean, message: String)
    fun onPositionDiscontinuity(positionMs: Long) {}
}

/**
 * Abstract player engine interface decoupling PlaybackController from concrete player implementations
 * (e.g. Media3 ExoPlayer).
 */
interface PlayerEngine {
    fun play(url: String, startPositionMs: Long = 0L)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
    fun setPlaybackSpeed(speed: Float)
    fun getPlaybackSpeed(): Float
    fun getCurrentPositionMs(): Long
    fun getDurationMs(): Long
    fun isPlaying(): Boolean
    fun isEnded(): Boolean
    fun isReady(): Boolean
    fun attachPlayerView(playerView: PlayerView)
    fun detachPlayerView(playerView: PlayerView)
    fun setListener(listener: PlayerEngineListener?)
    fun manualRetry()
}
