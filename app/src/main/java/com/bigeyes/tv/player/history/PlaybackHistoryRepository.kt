package com.bigeyes.tv.player.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.Serializable

/**
 * Playback History record for series and standalone media.
 */
data class PlaybackHistoryEntry(
    val seriesId: String,
    val seriesTitle: String,
    val episodeIndex: Int,
    val episodeNumber: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long = System.currentTimeMillis()
) : Serializable {

    /**
     * If the video playback position is within 30 seconds of the end,
     * the episode is considered fully completed.
     */
    val isCompleted: Boolean
        get() = duration > 0L && position >= (duration - COMPLETED_THRESHOLD_MS).coerceAtLeast(0L)

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("seriesId", seriesId)
            put("seriesTitle", seriesTitle)
            put("episodeIndex", episodeIndex)
            put("episodeNumber", episodeNumber)
            put("position", position)
            put("duration", duration)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        const val COMPLETED_THRESHOLD_MS = 30_000L

        fun fromJson(json: JSONObject): PlaybackHistoryEntry {
            return PlaybackHistoryEntry(
                seriesId = json.optString("seriesId"),
                seriesTitle = json.optString("seriesTitle"),
                episodeIndex = json.optInt("episodeIndex", 0),
                episodeNumber = json.optInt("episodeNumber", 1),
                position = json.optLong("position", 0L),
                duration = json.optLong("duration", 0L),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Thread-safe Playback History repository backed by SharedPreferences.
 */
class PlaybackHistoryRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveHistory(entry: PlaybackHistoryEntry) {
        if (entry.seriesId.isBlank()) return
        try {
            prefs.edit()
                .putString(KEY_PREFIX + entry.seriesId, entry.toJson().toString())
                .apply()
            Log.d(TAG, "History saved for seriesId=${entry.seriesId}, ep=${entry.episodeIndex}, pos=${entry.position}/${entry.duration}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history: ${e.message}")
        }
    }

    @Synchronized
    fun getHistory(seriesId: String): PlaybackHistoryEntry? {
        if (seriesId.isBlank()) return null
        val raw = prefs.getString(KEY_PREFIX + seriesId, null) ?: return null
        return try {
            PlaybackHistoryEntry.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse history entry for $seriesId: ${e.message}")
            null
        }
    }

    @Synchronized
    fun removeHistory(seriesId: String) {
        prefs.edit().remove(KEY_PREFIX + seriesId).apply()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "PlaybackHistoryRepo"
        private const val PREF_NAME = "bigeyes_tv_playback_history"
        private const val KEY_PREFIX = "history_"

        @Volatile
        private var INSTANCE: PlaybackHistoryRepository? = null

        fun getInstance(context: Context): PlaybackHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
