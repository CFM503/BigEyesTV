package com.bigeyes.tv.player.controller

import android.util.Log

/**
 * Completion Guard prevents duplicate onPlaybackCompleted events emitted by media player engines.
 * Guards using monotonic generation identifiers and episode keys, guaranteeing exactly-once
 * auto-advance execution per episode playback.
 */
class CompletionGuard {

    private var currentGenerationId: Long = 0L
    private var lastCompletedGenerationId: Long = -1L
    private var lastCompletedEpisodeKey: String? = null
    private var lastCompletionTimeMs: Long = 0L

    /**
     * Start a new playback cycle. Returns the new generation ID.
     */
    @Synchronized
    fun nextGeneration(): Long {
        currentGenerationId++
        return currentGenerationId
    }

    @Synchronized
    fun getCurrentGenerationId(): Long = currentGenerationId

    /**
     * Check if the completion event is legitimate and should proceed to next episode.
     * Guaranteed to return true at most once per generation and episode.
     */
    @Synchronized
    fun canComplete(generationId: Long, episodeKey: String?): Boolean {
        val now = System.currentTimeMillis()
        if (generationId != currentGenerationId) {
            Log.w(TAG, "Completion rejected: obsolete generationId $generationId (current: $currentGenerationId)")
            return false
        }
        if (lastCompletedGenerationId == generationId) {
            Log.w(TAG, "Completion rejected: duplicate callback for generationId $generationId")
            return false
        }
        if (episodeKey != null && episodeKey == lastCompletedEpisodeKey && (now - lastCompletionTimeMs < 3000L)) {
            Log.w(TAG, "Completion rejected: debounce window triggered for episodeKey $episodeKey")
            return false
        }

        lastCompletedGenerationId = generationId
        lastCompletedEpisodeKey = episodeKey
        lastCompletionTimeMs = now
        Log.i(TAG, "Completion guard passed for generationId: $generationId, episodeKey: $episodeKey")
        return true
    }

    @Synchronized
    fun reset() {
        lastCompletedGenerationId = -1L
        lastCompletedEpisodeKey = null
        lastCompletionTimeMs = 0L
    }

    companion object {
        private const val TAG = "CompletionGuard"
    }
}
