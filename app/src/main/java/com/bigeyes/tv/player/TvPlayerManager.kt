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
import com.bigeyes.tv.utils.AudioManagerVolumeController
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
class TvPlayerManager private constructor(private val context: Context) : PlaybackFacade {

    val controller: PlaybackController = PlaybackController.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val volumeController = AudioManagerVolumeController(context)
    private val listeners = CopyOnWriteArrayList<TvPlayerListener>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    override var currentUrl: String? = null
        private set

    @Volatile
    var nextUrl: String? = null
        private set

    @Volatile
    override var currentState: PlayerState = PlayerState.IDLE
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
            var previousSession = controller.session.value
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
                val nowBuffering = session.isBuffering || session.playbackState == PlaybackState.LOADING
                val wasBuffering = previousSession.isBuffering ||
                    previousSession.playbackState == PlaybackState.LOADING
                isBuffering = nowBuffering
                bufferingMessage = session.playbackHint.orEmpty()

                if (previousState != legacyState) {
                    listeners.forEach { it.onStateChanged(legacyState) }
                }

                if (nowBuffering != wasBuffering || session.playbackHint != previousSession.playbackHint) {
                    listeners.forEach {
                        it.onBufferingStateChanged(nowBuffering, session.playbackHint.orEmpty())
                    }
                }

                if (session.retryAttempt != previousSession.retryAttempt && session.retryAttempt > 0) {
                    listeners.forEach { it.onNetworkRetry(session.retryAttempt, session.retryMax) }
                }

                if (session.isNetworkInterrupted && !previousSession.isNetworkInterrupted) {
                    listeners.forEach { it.onNetworkInterrupted(session.position) }
                }

                if (session.playbackState == PlaybackState.PLAYING && previousState != PlayerState.READY) {
                    listeners.forEach { it.onPlaybackStarted(session.currentEpisode?.playUrl.orEmpty()) }
                } else if (session.playbackState == PlaybackState.STOPPED) {
                    listeners.forEach { it.onPlaybackStopped() }
                } else if (session.playbackState == PlaybackState.ERROR) {
                    listeners.forEach { it.onError(session.errorMessage ?: "播放出错") }
                }

                previousSession = session
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

    override fun play(url: String, startPositionMs: Long, title: String?) {
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

    override fun pause() {
        controller.dispatch(PlaybackCommand.Pause)
    }

    override fun resume() {
        controller.dispatch(PlaybackCommand.Resume)
    }

    fun togglePlayPause() {
        controller.dispatch(PlaybackCommand.TogglePlayPause)
    }

    override fun seekTo(positionMs: Long) {
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
    override fun setNextUrl(url: String?) {
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

    override fun playNext(): Boolean {
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

    override fun playPrevious(): Boolean {
        if (controller.episodeQueue.hasPrevious()) {
            controller.dispatch(PlaybackCommand.Previous)
            return true
        }
        seekTo(0L)
        return false
    }

    override fun stop() {
        currentUrl = null
        nextUrl = null
        controller.dispatch(PlaybackCommand.Stop)
    }

    /**
     * Tears down the playback pipeline. Called by [com.bigeyes.tv.service.TvReceiverService]
     * when the receiver goes away. Singletons are cleared so the next [getInstance] rebuilds
     * a fresh manager instead of handing out a released one.
     */
    fun release() {
        coroutineScope.cancel()
        controller.release()
        INSTANCE = null
    }

    override fun isPlaying(): Boolean = controller.playerEngine.isPlaying()

    override fun isEnded(): Boolean = controller.playerEngine.isEnded()

    override fun isReady(): Boolean = controller.playerEngine.isReady()

    override fun getDurationMs(): Long = controller.playerEngine.getDurationMs()

    override fun getCurrentPositionMs(): Long = controller.playerEngine.getCurrentPositionMs()

    /** Media volume in the AirPlay 0.0..1.0 range, mapped onto the device music stream. */
    override fun getPlaybackVolume(): Double = volumeController.getVolume() / 100.0

    /** Applies a volume in the AirPlay 0.0..1.0 range. */
    override fun setPlaybackVolume(volume: Double) {
        volumeController.setVolume((volume.coerceIn(0.0, 1.0) * 100).toInt())
    }

    fun setPlaybackVolumePercent(percent: Int) {
        volumeController.setVolume(percent)
    }

    fun getPlaybackVolumePercent(): Int = volumeController.getVolume()

    /** Display title of the current track, used when building DLNA DIDL-Lite metadata. */
    override fun currentTrackTitle(): String {
        val session = controller.session.value
        return session.currentEpisode?.episodeTitle
            ?: session.seriesTitle
            ?: session.currentEpisode?.seriesTitle
            ?: ""
    }

    /** 1-based track number of the current track within the active queue. */
    override fun currentTrackIndex(): Int = controller.session.value.currentIndex + 1

    fun manualRetry() {
        controller.dispatch(PlaybackCommand.Retry)
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
