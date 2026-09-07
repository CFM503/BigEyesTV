package com.bigeyes.tv.player.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.ui.PlayerView
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.engine.ExoPlayerEngine
import com.bigeyes.tv.player.engine.PlayerEngine
import com.bigeyes.tv.player.engine.PlayerEngineListener
import com.bigeyes.tv.player.history.PlaybackHistoryEntry
import com.bigeyes.tv.player.history.PlaybackHistoryRepository
import com.bigeyes.tv.player.model.Episode
import com.bigeyes.tv.player.model.EpisodeQueue
import com.bigeyes.tv.player.model.PlaybackSession
import com.bigeyes.tv.player.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified TV Playback Controller acting as the brain for video playback.
 * Coordinates EpisodeQueue, PlayerEngine, PlaybackHistoryRepository, and CompletionGuard.
 * Exposes an immutable StateFlow<PlaybackSession> for reactive UI observations.
 */
class PlaybackController private constructor(
    private val context: Context,
    val playerEngine: PlayerEngine = ExoPlayerEngine(context.applicationContext),
    val historyRepository: PlaybackHistoryRepository = PlaybackHistoryRepository.getInstance(context)
) : PlayerEngineListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val completionGuard = CompletionGuard()
    private val isSwitchingEpisode = AtomicBoolean(false)

    val episodeQueue = EpisodeQueue()

    private val _session = MutableStateFlow(PlaybackSession.INITIAL)
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private var progressTickerRunnable: Runnable? = null

    init {
        playerEngine.setListener(this)
        startProgressTicker()
    }

    /**
     * Central instruction dispatch endpoint.
     * All origins (Remote, Intent, DLNA, AirPlay, UI) funnel through here.
     */
    fun dispatch(command: PlaybackCommand) {
        Log.i(TAG, "Dispatch command: $command")
        when (command) {
            is PlaybackCommand.Play -> handlePlay(command.url, command.startPositionMs)
            is PlaybackCommand.Pause -> handlePause()
            is PlaybackCommand.Resume -> handleResume()
            is PlaybackCommand.TogglePlayPause -> handleTogglePlayPause()
            is PlaybackCommand.Stop -> handleStop()
            is PlaybackCommand.Next -> handleNext()
            is PlaybackCommand.Previous -> handlePrevious()
            is PlaybackCommand.Seek -> handleSeek(command.positionMs)
            is PlaybackCommand.SeekForward -> handleSeekRelative(command.stepMs)
            is PlaybackCommand.SeekBackward -> handleSeekRelative(-command.stepMs)
            is PlaybackCommand.SetSpeed -> handleSetSpeed(command.speed)
            is PlaybackCommand.PlayEpisode -> handlePlayEpisode(command.index, command.startPositionMs)
            is PlaybackCommand.PlayQueue -> handlePlayQueue(
                command.queue,
                command.startIndex,
                command.startPositionMs,
                command.autoPlayNext
            )
            is PlaybackCommand.CancelCountdown -> handleCancelCountdown()
            is PlaybackCommand.SetAutoPlayNext -> handleSetAutoPlayNext(command.enabled)
            is PlaybackCommand.Retry -> handleRetry()
        }
    }

    private fun handlePlay(url: String?, startPositionMs: Long) {
        if (!url.isNullOrBlank()) {
            val singleQueue = listOf(Episode.createSingle(url))
            handlePlayQueue(singleQueue, 0, startPositionMs, autoPlayNext = false)
        } else {
            handleResume()
        }
    }

    private fun handlePause() {
        playerEngine.pause()
        _session.update { it.copy(playbackState = PlaybackState.PAUSED) }
    }

    private fun handleResume() {
        val current = _session.value
        if (current.playbackState == PlaybackState.COMPLETED) {
            // Replay from beginning
            val ep = episodeQueue.current()
            if (ep != null) {
                playEpisodeInternal(ep, 0L)
                return
            }
        }
        playerEngine.resume()
    }

    private fun handleTogglePlayPause() {
        if (playerEngine.isPlaying()) {
            handlePause()
        } else {
            handleResume()
        }
    }

    private fun handleStop() {
        saveCurrentProgressToHistory()
        playerEngine.stop()
        completionGuard.reset()
        isSwitchingEpisode.set(false)
        _session.update {
            it.copy(
                playbackState = PlaybackState.STOPPED,
                position = 0L,
                duration = 0L,
                countdownRemainingSeconds = null
            )
        }
    }

    private fun handleNext() {
        if (!episodeQueue.hasNext()) {
            Log.w(TAG, "No next episode available in queue.")
            return
        }
        if (!isSwitchingEpisode.compareAndSet(false, true)) {
            Log.w(TAG, "Next command ignored: episode switch in progress.")
            return
        }
        saveCurrentProgressToHistory()
        val nextEp = episodeQueue.next()
        if (nextEp != null) {
            Log.i(TAG, "Navigating to next episode: ${nextEp.episodeNumber} (${nextEp.playUrl})")
            playEpisodeInternal(nextEp, 0L)
        } else {
            isSwitchingEpisode.set(false)
        }
    }

    private fun handlePrevious() {
        if (!episodeQueue.hasPrevious()) {
            Log.w(TAG, "No previous episode available in queue, seeking to 0.")
            playerEngine.seekTo(0L)
            return
        }
        if (!isSwitchingEpisode.compareAndSet(false, true)) {
            Log.w(TAG, "Previous command ignored: episode switch in progress.")
            return
        }
        saveCurrentProgressToHistory()
        val prevEp = episodeQueue.previous()
        if (prevEp != null) {
            Log.i(TAG, "Navigating to previous episode: ${prevEp.episodeNumber} (${prevEp.playUrl})")
            playEpisodeInternal(prevEp, 0L)
        } else {
            isSwitchingEpisode.set(false)
        }
    }

    private fun handleSeek(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        playerEngine.seekTo(safePos)
        _session.update { it.copy(position = safePos) }
    }

    private fun handleSeekRelative(deltaMs: Long) {
        val curPos = playerEngine.getCurrentPositionMs()
        val duration = playerEngine.getDurationMs()
        val target = if (duration > 0) {
            (curPos + deltaMs).coerceIn(0L, duration)
        } else {
            (curPos + deltaMs).coerceAtLeast(0L)
        }
        handleSeek(target)
    }

    private fun handleSetSpeed(speed: Float) {
        playerEngine.setPlaybackSpeed(speed)
        _session.update { it.copy(speed = speed) }
    }

    private fun handlePlayEpisode(index: Int, startPositionMs: Long) {
        if (!episodeQueue.setCurrentIndex(index)) {
            Log.w(TAG, "Invalid episode index $index for queue of size ${episodeQueue.size}")
            return
        }
        if (!isSwitchingEpisode.compareAndSet(false, true)) {
            Log.w(TAG, "PlayEpisode ignored: episode switch in progress.")
            return
        }
        saveCurrentProgressToHistory()
        val ep = episodeQueue.current()
        if (ep != null) {
            playEpisodeInternal(ep, startPositionMs)
        } else {
            isSwitchingEpisode.set(false)
        }
    }

    private fun handlePlayQueue(
        queue: List<Episode>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlayNext: Boolean
    ) {
        if (queue.isEmpty()) {
            Log.w(TAG, "Cannot play empty queue.")
            return
        }
        saveCurrentProgressToHistory()
        episodeQueue.setQueue(queue, startIndex)
        val ep = episodeQueue.current() ?: queue[0]

        _session.update {
            it.copy(
                seriesId = ep.seriesId,
                seriesTitle = ep.seriesTitle,
                currentEpisode = ep,
                currentIndex = episodeQueue.currentIndex,
                autoPlayNext = autoPlayNext,
                isLastEpisode = episodeQueue.isLast()
            )
        }

        val resumePos = if (startPositionMs > 0L) {
            startPositionMs
        } else {
            resolveResumePosition(ep)
        }

        playEpisodeInternal(ep, resumePos)
    }

    private fun handleCancelCountdown() {
        Log.i(TAG, "User cancelled auto next countdown.")
        _session.update {
            it.copy(
                countdownRemainingSeconds = null,
                autoPlayNext = false
            )
        }
    }

    private fun handleSetAutoPlayNext(enabled: Boolean) {
        Log.i(TAG, "Auto play next changed: $enabled")
        _session.update { it.copy(autoPlayNext = enabled) }
    }

    private fun handleRetry() {
        Log.i(TAG, "Manual retry requested.")
        playerEngine.manualRetry()
    }

    private fun playEpisodeInternal(episode: Episode, startPositionMs: Long) {
        val generationId = completionGuard.nextGeneration()
        Log.i(TAG, "Starting episode: gen=$generationId, ep=${episode.episodeNumber}, url=${episode.playUrl}, pos=$startPositionMs")

        _session.update {
            it.copy(
                seriesId = episode.seriesId,
                seriesTitle = episode.seriesTitle,
                currentEpisode = episode,
                currentIndex = episodeQueue.currentIndex,
                playbackState = PlaybackState.LOADING,
                position = startPositionMs,
                duration = episode.duration,
                countdownRemainingSeconds = null,
                errorMessage = null,
                isLastEpisode = episodeQueue.isLast()
            )
        }

        playerEngine.play(episode.playUrl, startPositionMs)

        // Safety fallback to unlock switching mutex after delay
        mainHandler.postDelayed({
            isSwitchingEpisode.set(false)
        }, 3000L)
    }

    private fun resolveResumePosition(episode: Episode): Long {
        val history = historyRepository.getHistory(episode.seriesId) ?: return 0L
        if (history.episodeIndex == episode.episodeIndex && !history.isCompleted) {
            Log.i(TAG, "Auto-resuming ${episode.seriesTitle} ep=${episode.episodeIndex} at ${history.position}ms")
            return history.position
        }
        return 0L
    }

    private fun saveCurrentProgressToHistory() {
        val current = _session.value
        val ep = current.currentEpisode ?: return
        val pos = playerEngine.getCurrentPositionMs()
        val dur = playerEngine.getDurationMs()

        if (ep.seriesId.isNotBlank() && (pos > 0L || dur > 0L)) {
            val entry = PlaybackHistoryEntry(
                seriesId = ep.seriesId,
                seriesTitle = ep.seriesTitle,
                episodeIndex = episodeQueue.currentIndex,
                episodeNumber = ep.episodeNumber,
                position = pos,
                duration = dur
            )
            historyRepository.saveHistory(entry)
        }
    }

    // ==================== PlayerEngineListener Callbacks ====================

    override fun onEngineStateChanged(state: PlaybackState) {
        _session.update { it.copy(playbackState = state) }
    }

    override fun onPlaybackStarted(url: String) {
        isSwitchingEpisode.set(false)
        _session.update {
            it.copy(
                playbackState = PlaybackState.PLAYING,
                errorMessage = null
            )
        }
    }

    override fun onPlaybackCompleted() {
        val current = _session.value
        val ep = current.currentEpisode
        val genId = completionGuard.getCurrentGenerationId()

        if (!completionGuard.canComplete(genId, ep?.playUrl)) {
            Log.w(TAG, "Duplicate or guarded completion event dropped.")
            return
        }

        saveCurrentProgressToHistory()
        isSwitchingEpisode.set(false)

        if (current.autoPlayNext && episodeQueue.hasNext()) {
            val nextEp = episodeQueue.next()
            if (nextEp != null) {
                Log.i(TAG, "Auto advancing to next episode: ${nextEp.episodeNumber}")
                playEpisodeInternal(nextEp, 0L)
                return
            }
        }

        // Reached end of queue or autoPlayNext disabled
        val isLast = episodeQueue.isLast()
        Log.i(TAG, "Playback ended: isLast=$isLast, autoPlayNext=${current.autoPlayNext}")
        _session.update {
            it.copy(
                playbackState = PlaybackState.COMPLETED,
                countdownRemainingSeconds = null,
                isLastEpisode = isLast
            )
        }
    }

    override fun onError(error: String) {
        isSwitchingEpisode.set(false)
        Log.e(TAG, "Playback error received from engine: $error")
        _session.update {
            it.copy(
                playbackState = PlaybackState.ERROR,
                errorMessage = error,
                countdownRemainingSeconds = null
            )
        }
    }

    override fun onBufferingStateChanged(isBuffering: Boolean, message: String) {
        _session.update {
            if (isBuffering) {
                it.copy(playbackState = PlaybackState.BUFFERING)
            } else {
                it.copy(playbackState = if (playerEngine.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED)
            }
        }
    }

    // ==================== Progress & 10s Countdown Ticker ====================

    private fun startProgressTicker() {
        stopProgressTicker()
        val r = object : Runnable {
            override fun run() {
                updateProgressAndCountdown()
                mainHandler.postDelayed(this, 500L)
            }
        }
        progressTickerRunnable = r
        mainHandler.post(r)
    }

    private fun stopProgressTicker() {
        progressTickerRunnable?.let { mainHandler.removeCallbacks(it) }
        progressTickerRunnable = null
    }

    private fun updateProgressAndCountdown() {
        val state = _session.value.playbackState
        if (state != PlaybackState.PLAYING && state != PlaybackState.PAUSED && state != PlaybackState.BUFFERING) {
            return
        }

        val pos = playerEngine.getCurrentPositionMs()
        val dur = playerEngine.getDurationMs()

        // 10-Second Countdown logic
        val remainingMs = dur - pos
        val autoNext = _session.value.autoPlayNext
        val hasNext = episodeQueue.hasNext()

        val countdownSec = if (dur > 0L && remainingMs in 1..COUNTDOWN_THRESHOLD_MS && autoNext && hasNext) {
            ((remainingMs + 999) / 1000).toInt()
        } else {
            null
        }

        _session.update {
            it.copy(
                position = pos,
                duration = dur,
                countdownRemainingSeconds = countdownSec
            )
        }
    }

    fun attachPlayerView(playerView: PlayerView) {
        playerEngine.attachPlayerView(playerView)
    }

    fun detachPlayerView(playerView: PlayerView) {
        playerEngine.detachPlayerView(playerView)
    }

    fun release() {
        stopProgressTicker()
        saveCurrentProgressToHistory()
        playerEngine.release()
        completionGuard.reset()
    }

    companion object {
        private const val TAG = "PlaybackController"
        private const val COUNTDOWN_THRESHOLD_MS = 10_000L

        @Volatile
        private var INSTANCE: PlaybackController? = null

        fun getInstance(context: Context): PlaybackController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
