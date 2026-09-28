package com.bigeyes.tv.player.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.model.PlaybackSession
import com.bigeyes.tv.player.model.PlaybackState

/**
 * Immutable projection of a [PlaybackSession] that is safe to broadcast back to the
 * companion BigEyes phone app.
 *
 * Kept free of Android framework types so that state mapping stays unit testable.
 */
data class TvStatusSnapshot(
    val state: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val episodeIndex: Int,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val autoPlayNext: Boolean
) {
    /** Identity of the "interesting" parts of a status; plain progress does not change it. */
    val signature: String
        get() = "$state|$seriesId|$episodeIndex"

    companion object {
        fun from(session: PlaybackSession): TvStatusSnapshot {
            val episode = session.currentEpisode
            return TvStatusSnapshot(
                state = mapState(session.playbackState),
                seriesId = session.seriesId,
                seriesTitle = session.seriesTitle,
                episodeIndex = session.currentIndex,
                episodeNumber = episode?.episodeNumber ?: (session.currentIndex + 1),
                episodeTitle = episode?.episodeTitle,
                positionMs = session.position,
                durationMs = session.duration,
                autoPlayNext = session.autoPlayNext
            )
        }

        fun mapState(state: PlaybackState): String = when (state) {
            PlaybackState.PLAYING,
            PlaybackState.BUFFERING,
            PlaybackState.LOADING -> PlaybackIntentContract.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackIntentContract.STATE_PAUSED
            PlaybackState.COMPLETED -> PlaybackIntentContract.STATE_COMPLETED
            PlaybackState.ERROR -> PlaybackIntentContract.STATE_ERROR
            PlaybackState.STOPPED,
            PlaybackState.IDLE -> PlaybackIntentContract.STATE_STOPPED
        }
    }
}

/**
 * Rate limiter for status broadcasts: state / episode transitions are always reported
 * immediately, while pure progress updates are coalesced to at most one per interval.
 */
class StatusThrottle(private val intervalMs: Long) {

    private var lastSignature: String? = null
    private var lastSentAt = 0L
    private var hasSent = false

    fun shouldSend(signature: String, now: Long): Boolean {
        if (!hasSent || signature != lastSignature) return true
        return now - lastSentAt >= intervalMs
    }

    fun markSent(signature: String, now: Long) {
        lastSignature = signature
        lastSentAt = now
        hasSent = true
    }

    fun reset() {
        lastSignature = null
        lastSentAt = 0L
        hasSent = false
    }
}

/**
 * Pushes playback status from BigEyesTV to the BigEyes phone app so that the phone control bar
 * mirrors progress, play/pause state and episode transitions in real time.
 */
class TvStatusReporter(private val context: Context) {

    private val throttle = StatusThrottle(PROGRESS_INTERVAL_MS)
    private var companionInstalled: Boolean? = null

    /**
     * The controller starts in IDLE; broadcasting that initial state would race with a phone
     * that just registered its status receiver for a fresh cast. Start reporting only once
     * playback actually became active, so real STOPPED transitions are still delivered later.
     */
    private var hasSeenActiveState = false

    fun onSessionChanged(session: PlaybackSession, now: Long = System.currentTimeMillis()) {
        if (!isCompanionInstalled()) return

        val snapshot = TvStatusSnapshot.from(session)
        if (!hasSeenActiveState) {
            if (snapshot.state == PlaybackIntentContract.STATE_STOPPED) return
            hasSeenActiveState = true
        }

        if (!throttle.shouldSend(snapshot.signature, now)) return
        throttle.markSent(snapshot.signature, now)

        val intent = buildIntent(snapshot)
        try {
            context.sendBroadcast(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to broadcast playback status: ${e.message}")
        }
    }

    fun reset() = throttle.reset()

    private fun buildIntent(snapshot: TvStatusSnapshot): Intent {
        return Intent(PlaybackIntentContract.ACTION_STATUS_UPDATE).apply {
            setPackage(PlaybackIntentContract.PACKAGE_BIGEYES)
            putExtra(PlaybackIntentContract.EXTRA_STATE, snapshot.state)
            putExtra(PlaybackIntentContract.EXTRA_SERIES_ID, snapshot.seriesId)
            putExtra(PlaybackIntentContract.EXTRA_SERIES_TITLE, snapshot.seriesTitle)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_INDEX, snapshot.episodeIndex)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_NUMBER, snapshot.episodeNumber)
            putExtra(PlaybackIntentContract.EXTRA_EPISODE_TITLE, snapshot.episodeTitle)
            putExtra(PlaybackIntentContract.EXTRA_POSITION_MS, snapshot.positionMs)
            putExtra(PlaybackIntentContract.EXTRA_DURATION_MS, snapshot.durationMs)
            putExtra(PlaybackIntentContract.EXTRA_AUTO_PLAY_NEXT, snapshot.autoPlayNext)
        }
    }

    private fun isCompanionInstalled(): Boolean {
        companionInstalled?.let { return it }
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PlaybackIntentContract.PACKAGE_BIGEYES,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PlaybackIntentContract.PACKAGE_BIGEYES, 0)
            }
            true
        } catch (e: Throwable) {
            false
        }
        companionInstalled = installed
        return installed
    }

    companion object {
        private const val TAG = "TvStatusReporter"
        const val PROGRESS_INTERVAL_MS = 1000L
    }
}
