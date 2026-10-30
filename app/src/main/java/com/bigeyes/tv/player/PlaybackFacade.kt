package com.bigeyes.tv.player

import com.bigeyes.tv.player.model.Episode

/**
 * Narrow playback surface required by the DLNA / AirPlay / BigEyes Companion HTTP handlers.
 *
 * [TvPlayerManager] implements it; tests substitute a fake so the real protocol handlers can be
 * exercised without an Android [android.content.Context] or an ExoPlayer instance.
 */
interface PlaybackFacade {

    /** URL of the media currently loaded, or null when idle. */
    val currentUrl: String?

    /** Legacy low level player state reported to [TvPlayerListener]s. */
    val currentState: PlayerState

    fun play(url: String, startPositionMs: Long = 0L, title: String? = null)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)

    fun setNextUrl(url: String?)
    fun playNext(): Boolean
    fun playPrevious(): Boolean

    fun getDurationMs(): Long
    fun getCurrentPositionMs(): Long
    fun isPlaying(): Boolean
    fun isEnded(): Boolean
    fun isReady(): Boolean

    /** Media volume in the AirPlay 0.0..1.0 range. */
    fun getPlaybackVolume(): Double

    /** Applies a volume in the AirPlay 0.0..1.0 range. */
    fun setPlaybackVolume(volume: Double)

    /** Display title of the current track, used for DIDL-Lite metadata. */
    fun currentTrackTitle(): String

    /** 1-based track number of the current track. */
    fun currentTrackIndex(): Int

    // BigEyes LAN Companion API extensions
    fun playQueue(
        queue: List<Episode>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlayNext: Boolean = true
    ) {
        if (queue.isNotEmpty() && startIndex in queue.indices) {
            val ep = queue[startIndex]
            play(ep.playUrl, startPositionMs, ep.toDisplayTitle())
        }
    }

    fun playEpisode(index: Int, startPositionMs: Long = 0L) {
        seekTo(startPositionMs)
    }

    fun setPlaybackSpeed(speed: Float) {}
    fun getPlaybackSpeed(): Float = 1.0f

    fun getSeriesId(): String? = null
    fun getSeriesTitle(): String? = currentTrackTitle()
    fun getCurrentEpisode(): Episode? = null
    fun isBuffering(): Boolean = false
    fun isAutoPlayNext(): Boolean = true
    fun getErrorMessage(): String? = null
    fun bringActivityToFront() {}
}
