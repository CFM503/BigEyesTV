package com.bigeyes.tv.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.ui.PlayerView
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.controller.PlaybackController
import com.bigeyes.tv.player.model.Episode
import com.bigeyes.tv.player.model.PlaybackState
import com.bigeyes.tv.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Backward-compatible Facade adapter delegating to the unified PlaybackController.
 * Ensures that existing DLNA, AirPlay, background services, and legacy call-sites
 * remain 100% operational without regression while leveraging the new Episode Queue engine.
 */
class TvPlayerManager private constructor(private val context: Context) {

    val controller: PlaybackController = PlaybackController.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<TvPlayerListener>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var nextUrl: String? = null
        private set

    @Volatile
    var currentState: PlayerState = PlayerState.IDLE
        private set

    @Volatile
    var isBuffering: Boolean = false
        private set

    @Volatile
    var bufferingMessage: String = ""
        private set

    init {
        // Observe unified PlaybackController session and project into legacy PlayerState
        coroutineScope.launch {
            controller.session.collect { session ->
                currentUrl = session.currentEpisode?.playUrl
                val legacyState = when (session.playbackState) {
                    PlaybackState.IDLE -> PlayerState.IDLE
                    PlaybackState.LOADING -> PlayerState.BUFFERING
                    PlaybackState.BUFFERING -> PlayerState.BUFFERING
                    PlaybackState.PLAYING -> PlayerState.READY
                    PlaybackState.PAUSED -> PlayerState.READY
                    PlaybackState.COMPLETED -> PlayerState.ENDED
                    PlaybackState.ERROR -> PlayerState.ERROR
                    PlaybackState.STOPPED -> PlayerState.IDLE
                }

                val previousState = currentState
                currentState = legacyState
                isBuffering = session.isBuffering

                if (previousState != legacyState) {
                    listeners.forEach { it.onStateChanged(legacyState) }
                }

                if (session.playbackState == PlaybackState.PLAYING && previousState != PlayerState.READY) {
                    listeners.forEach { it.onPlaybackStarted(session.currentEpisode?.playUrl.orEmpty()) }
                } else if (session.playbackState == PlaybackState.STOPPED) {
                    listeners.forEach { it.onPlaybackStopped() }
                } else if (session.playbackState == PlaybackState.ERROR) {
                    listeners.forEach { it.onError(session.errorMessage ?: "播放出错") }
                }
            }
        }
    }

    fun attachPlayerView(playerView: PlayerView) {
        controller.attachPlayerView(playerView)
    }

    fun detachPlayerView(playerView: PlayerView) {
        controller.detachPlayerView(playerView)
    }

    fun addListener(listener: TvPlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TvPlayerListener) {
        listeners.remove(listener)
    }

    fun play(url: String, startPositionMs: Long = 0L, title: String? = null) {
        Log.i(TAG, "TvPlayerManager.play url=$url, startPositionMs=$startPositionMs, title=$title")
        currentUrl = url
        controller.dispatch(PlaybackCommand.Play(url, startPositionMs, title))
        bringActivityToFront()
    }

    private fun bringActivityToFront() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring MainActivity to front: ${e.message}")
        }
    }

    fun pause() {
        controller.dispatch(PlaybackCommand.Pause)
    }

    fun resume() {
        controller.dispatch(PlaybackCommand.Resume)
    }

    fun togglePlayPause() {
        controller.dispatch(PlaybackCommand.TogglePlayPause)
    }

    fun seekTo(positionMs: Long) {
        controller.dispatch(PlaybackCommand.Seek(positionMs))
    }

    fun setPlaybackSpeed(speed: Float) {
        controller.dispatch(PlaybackCommand.SetSpeed(speed))
    }

    fun getPlaybackSpeed(): Float {
        return controller.playerEngine.getPlaybackSpeed()
    }

    /**
     * Preloads next URL. If an episode queue is present, updates next episode's playUrl;
     * otherwise prepares nextUrl for playback.
     */
    fun setNextUrl(url: String?) {
        Log.i(TAG, "TvPlayerManager.setNextUrl: $url")
        nextUrl = url
        if (!url.isNullOrBlank()) {
            val queue = controller.episodeQueue
            val nextIndex = queue.currentIndex + 1
            if (nextIndex < queue.size) {
                queue.updateEpisodeUrl(nextIndex, url)
            } else if (queue.isEmpty || queue.size == 1) {
                // If queue only has 1 episode, append the next episode dynamically
                val current = queue.current()
                val nextEp = Episode(
                    seriesId = current?.seriesId ?: "stream_series",
                    seriesTitle = current?.seriesTitle ?: "连续播放",
                    seasonNumber = current?.seasonNumber ?: 1,
                    episodeNumber = (current?.episodeNumber ?: 1) + 1,
                    episodeTitle = "下一集",
                    episodeIndex = queue.size,
                    playUrl = url
                )
                val newQueue = queue.episodes + nextEp
                queue.setQueue(newQueue, queue.currentIndex)
                controller.dispatch(PlaybackCommand.SetAutoPlayNext(true))
            }
        }
    }

    fun playNext(): Boolean {
        if (controller.episodeQueue.hasNext()) {
            controller.dispatch(PlaybackCommand.Next)
            return true
        }
        val next = nextUrl
        if (!next.isNullOrBlank()) {
            nextUrl = null
            play(next, 0L)
            return true
        }
        return false
    }

    fun playPrevious(): Boolean {
        if (controller.episodeQueue.hasPrevious()) {
            controller.dispatch(PlaybackCommand.Previous)
            return true
        }
        seekTo(0L)
        return false
    }

    fun stop() {
        currentUrl = null
        nextUrl = null
        controller.dispatch(PlaybackCommand.Stop)
    }

    fun release() {
        coroutineScope.cancel()
        controller.release()
    }

    fun isPlaying(): Boolean = controller.playerEngine.isPlaying()

    fun isEnded(): Boolean = controller.playerEngine.isEnded()

    fun isReady(): Boolean = controller.playerEngine.isReady()

    fun getDurationMs(): Long = controller.playerEngine.getDurationMs()

    fun getCurrentPositionMs(): Long = controller.playerEngine.getCurrentPositionMs()

    fun manualRetry() {
        controller.dispatch(PlaybackCommand.Retry)
    }

    fun cancelAndReturnToStandby() {
        stop()
    }

    companion object {
        private const val TAG = "TvPlayerManager"

        @Volatile
        private var INSTANCE: TvPlayerManager? = null

        fun getInstance(context: Context): TvPlayerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TvPlayerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
