package com.bigeyes.tv.player.contract

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.model.Episode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standard Android Intent contract for BigEyes -> BigEyesTV inter-process communication.
 * Validates external input defensively to prevent crashes and sanitize payloads.
 */
object PlaybackIntentContract {

    private const val TAG = "PlaybackIntentContract"

    // Standard Actions
    const val ACTION_PLAY = "com.bigeyes.tv.action.PLAY"
    const val ACTION_PLAY_QUEUE = "com.bigeyes.tv.action.PLAY_QUEUE"
    const val ACTION_NEXT = "com.bigeyes.tv.action.NEXT"
    const val ACTION_PREVIOUS = "com.bigeyes.tv.action.PREVIOUS"
    const val ACTION_PAUSE = "com.bigeyes.tv.action.PAUSE"
    const val ACTION_RESUME = "com.bigeyes.tv.action.RESUME"
    const val ACTION_STOP = "com.bigeyes.tv.action.STOP"
    const val ACTION_SEEK = "com.bigeyes.tv.action.SEEK"

    // Extras
    const val EXTRA_SERIES_ID = "extra_series_id"
    const val EXTRA_SERIES_TITLE = "extra_series_title"
    const val EXTRA_EPISODE_INDEX = "extra_episode_index"
    const val EXTRA_EPISODE_NUMBER = "extra_episode_number"
    const val EXTRA_EPISODE_TITLE = "extra_episode_title"
    const val EXTRA_PLAY_URL = "extra_play_url"
    const val EXTRA_EPISODE_QUEUE = "extra_episode_queue"
    const val EXTRA_SEEK_POSITION = "extra_seek_position"
    const val EXTRA_AUTO_PLAY_NEXT = "extra_auto_play_next"

    /**
     * Parse and validate an incoming Intent safely into a PlaybackCommand.
     * Returns null if intent is not recognized or contains fatal errors.
     */
    fun parseCommand(intent: Intent?): PlaybackCommand? {
        if (intent == null) return null
        val action = intent.action ?: return null

        return try {
            when (action) {
                ACTION_PLAY -> parsePlayIntent(intent)
                ACTION_PLAY_QUEUE -> parsePlayQueueIntent(intent)
                ACTION_NEXT -> PlaybackCommand.Next
                ACTION_PREVIOUS -> PlaybackCommand.Previous
                ACTION_PAUSE -> PlaybackCommand.Pause
                ACTION_RESUME -> PlaybackCommand.Resume
                ACTION_STOP -> PlaybackCommand.Stop
                ACTION_SEEK -> {
                    val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                    PlaybackCommand.Seek(pos.coerceAtLeast(0L))
                }
                Intent.ACTION_VIEW -> {
                    // Fallback for standard Android VIEW intents (e.g. clicking a stream link)
                    val dataUri = intent.dataString
                    if (!dataUri.isNullOrBlank()) {
                        PlaybackCommand.Play(dataUri, 0L)
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent: ${e.message}", e)
            null
        }
    }

    private fun parsePlayIntent(intent: Intent): PlaybackCommand? {
        val playUrl = intent.getStringExtra(EXTRA_PLAY_URL)
            ?: intent.dataString

        if (playUrl.isNullOrBlank()) {
            Log.w(TAG, "ACTION_PLAY missing EXTRA_PLAY_URL and dataUri")
            return null
        }

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: "single_series"
        val seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE) ?: "投屏播放"
        val epIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0)
        val epNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, epIndex + 1)
        val epTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: ""
        val startPos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)

        // Check if a queue is also bundled with ACTION_PLAY
        val rawQueue = intent.getStringExtra(EXTRA_EPISODE_QUEUE)
        if (!rawQueue.isNullOrBlank()) {
            val episodes = parseEpisodesJson(rawQueue)
            if (episodes.isNotEmpty()) {
                val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY_NEXT, true)
                return PlaybackCommand.PlayQueue(episodes, epIndex, startPos, autoPlay)
            }
        }

        // Single URL playback fallback
        val singleEpisode = Episode(
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            seasonNumber = 1,
            episodeNumber = epNumber,
            episodeTitle = epTitle,
            episodeIndex = epIndex,
            playUrl = playUrl,
            thumbnail = null,
            duration = 0L
        )
        return PlaybackCommand.PlayQueue(
            queue = listOf(singleEpisode),
            startIndex = 0,
            startPositionMs = startPos,
            autoPlayNext = false // Single episode has no next
        )
    }

    private fun parsePlayQueueIntent(intent: Intent): PlaybackCommand? {
        val rawQueue = intent.getStringExtra(EXTRA_EPISODE_QUEUE)
        val startIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0)
        val startPos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
        val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY_NEXT, true)

        if (rawQueue.isNullOrBlank()) {
            // Fallback: check if single playUrl is provided
            val playUrl = intent.getStringExtra(EXTRA_PLAY_URL)
            if (!playUrl.isNullOrBlank()) {
                return parsePlayIntent(intent)
            }
            Log.w(TAG, "ACTION_PLAY_QUEUE missing EXTRA_EPISODE_QUEUE")
            return null
        }

        val episodes = parseEpisodesJson(rawQueue)
        if (episodes.isEmpty()) {
            Log.w(TAG, "Parsed episode queue is empty")
            return null
        }

        return PlaybackCommand.PlayQueue(
            queue = episodes,
            startIndex = startIndex,
            startPositionMs = startPos,
            autoPlayNext = autoPlay
        )
    }

    /**
     * Parse JSON Array of episodes. Supports both full JSON episode objects
     * and array of simple stream URL strings.
     */
    fun parseEpisodesJson(jsonStr: String): List<Episode> {
        val list = mutableListOf<Episode>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                when (item) {
                    is JSONObject -> {
                        list.add(Episode.fromJson(item))
                    }
                    is String -> {
                        if (item.isNotBlank()) {
                            list.add(
                                Episode(
                                    seriesId = "series_queue",
                                    seriesTitle = "剧集列表",
                                    seasonNumber = 1,
                                    episodeNumber = i + 1,
                                    episodeTitle = "第 ${i + 1} 集",
                                    episodeIndex = i,
                                    playUrl = item
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse episodes JSON: ${e.message}")
        }
        return list
    }

    /**
     * Helper to build a play intent.
     */
    fun buildPlayIntent(context: Context, playUrl: String, title: String? = null): Intent {
        return Intent(context, com.bigeyes.tv.ui.MainActivity::class.java).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_PLAY_URL, playUrl)
            if (title != null) putExtra(EXTRA_SERIES_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    /**
     * Helper to build a play queue intent.
     */
    fun buildPlayQueueIntent(
        context: Context,
        episodes: List<Episode>,
        startIndex: Int = 0,
        startPosMs: Long = 0L,
        autoPlayNext: Boolean = true
    ): Intent {
        val array = JSONArray()
        episodes.forEach { array.put(it.toJson()) }

        return Intent(context, com.bigeyes.tv.ui.MainActivity::class.java).apply {
            action = ACTION_PLAY_QUEUE
            putExtra(EXTRA_EPISODE_QUEUE, array.toString())
            putExtra(EXTRA_EPISODE_INDEX, startIndex)
            putExtra(EXTRA_SEEK_POSITION, startPosMs)
            putExtra(EXTRA_AUTO_PLAY_NEXT, autoPlayNext)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}
