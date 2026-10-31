package com.bigeyes.tv.server

import android.util.Log
import com.bigeyes.tv.BuildConfig
import com.bigeyes.tv.player.PlaybackFacade
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.player.model.Episode
import com.bigeyes.tv.utils.DeviceIdentity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.HashMap

/**
 * BigEyes LAN Companion Protocol v1.0 Handler.
 * Provides RESTful JSON API endpoints for direct casting, full episode queue handover,
 * real-time remote playback control, and 1000ms status synchronization over Wi-Fi.
 */
class BigEyesCompanionHandler(
    private val playerManager: PlaybackFacade,
    private val deviceIdentity: DeviceIdentity
) {
    companion object {
        private const val TAG = "BigEyesCompanionHandler"
        private const val MIME_JSON = "application/json; charset=utf-8"
    }

    fun canHandle(uri: String): Boolean {
        return uri.startsWith("/bigeyes/api/") || uri.startsWith("/bigeyes/api")
    }

    fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == NanoHTTPD.Method.OPTIONS) {
            val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "")
            addCorsHeaders(resp)
            return resp
        }

        Log.i(TAG, "Incoming Companion Request: $method $uri from ${session.remoteIpAddress}")

        return when {
            uri == "/bigeyes/api/info" && method == NanoHTTPD.Method.GET -> handleGetInfo()
            uri == "/bigeyes/api/status" && method == NanoHTTPD.Method.GET -> handleGetStatus()
            uri == "/bigeyes/api/play" && method == NanoHTTPD.Method.POST -> handlePlay(session)
            uri == "/bigeyes/api/control" && method == NanoHTTPD.Method.POST -> handleControl(session)
            else -> jsonResponse(Response.Status.NOT_FOUND, 404, "Unknown endpoint: $uri")
        }
    }

    private fun handleGetInfo(): Response {
        val json = JSONObject().apply {
            put("code", 200)
            put("success", true)
            put("app", "BigEyesTV")
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("deviceId", deviceIdentity.deviceId)
            put("deviceName", deviceIdentity.deviceName)
            put("friendlyName", deviceIdentity.deviceName)
            put("companionProtocolVersion", "1.0")
            put("features", JSONArray().apply {
                put("queue")
                put("anti_hotlink")
                put("auto_next")
                put("speed")
                put("status_sync")
            })
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleGetStatus(): Response {
        val ep = playerManager.getCurrentEpisode()

        val stateStr = when {
            playerManager.isPlaying() -> "PLAYING"
            playerManager.isEnded() -> "COMPLETED"
            playerManager.isBuffering -> "BUFFERING"
            playerManager.currentState == PlayerState.ERROR -> "ERROR"
            playerManager.currentState == PlayerState.READY -> "PAUSED"
            playerManager.currentState == PlayerState.BUFFERING -> "BUFFERING"
            else -> "STOPPED"
        }

        val trackIndex = playerManager.currentTrackIndex()
        val epIndex = (trackIndex - 1).coerceAtLeast(0)

        val posMs = playerManager.getCurrentPositionMs()
        val durMs = playerManager.getDurationMs()

        val json = JSONObject().apply {
            put("code", 200)
            put("success", true)
            put("state", stateStr)
            put("seriesId", playerManager.getSeriesId() ?: "series")
            put("seriesTitle", playerManager.getSeriesTitle() ?: "")
            put("episodeIndex", epIndex)
            put("episodeNumber", ep?.episodeNumber ?: (epIndex + 1))
            put("episodeTitle", ep?.episodeTitle ?: playerManager.currentTrackTitle())
            put("positionMs", posMs)
            put("currentPositionMs", posMs)
            put("durationMs", durMs)
            put("autoPlayNext", playerManager.isAutoPlayNext())
            put("speed", playerManager.getPlaybackSpeed().toDouble())
            put("isBuffering", playerManager.isBuffering)
            put("errorMessage", playerManager.getErrorMessage() ?: "")
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePlay(session: IHTTPSession): Response {
        val bodyStr = readBodyString(session)
        if (bodyStr.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, 400, "Empty payload")
        }

        return try {
            val json = JSONObject(bodyStr)
            val seriesId = json.optString("seriesId", "single_series")
            val seriesTitle = json.optString("seriesTitle", "投屏播放")
            val seasonNumber = json.optInt("seasonNumber", 1)
            val episodeIndex = if (json.has("startIndex")) json.optInt("startIndex", 0) else json.optInt("episodeIndex", 0)
            val episodeNumber = json.optInt("episodeNumber", episodeIndex + 1)
            val episodeTitle = json.optString("episodeTitle", "")
            val positionMs = if (json.has("startPositionMs")) json.optLong("startPositionMs", 0L) else json.optLong("positionMs", 0L)
            val autoPlayNext = json.optBoolean("autoPlayNext", true)
            val playUrl = json.optString("playUrl").ifBlank { json.optString("url", "") }

            // Anti-hotlink request headers
            val headersMap = mutableMapOf<String, String>()
            json.optJSONObject("headers")?.let { hObj ->
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hObj.optString(k)
                    if (v.isNotBlank()) headersMap[k] = v
                }
            }

            // Episode queue (Queue Handover)
            val queueList = mutableListOf<Episode>()
            val queueArray = json.optJSONArray("queue")
            if (queueArray != null && queueArray.length() > 0) {
                for (i in 0 until queueArray.length()) {
                    val qItem = queueArray.getJSONObject(i)
                    val ep = Episode.fromJson(qItem)
                    val fixedEp = ep.copy(
                        seriesId = if (ep.seriesId == "single_series" && seriesId.isNotBlank()) seriesId else ep.seriesId,
                        seriesTitle = if (ep.seriesTitle.isBlank() && seriesTitle.isNotBlank()) seriesTitle else ep.seriesTitle
                    )
                    queueList.add(fixedEp)
                }
            }

            if (queueList.isEmpty()) {
                if (playUrl.isBlank()) {
                    return jsonResponse(Response.Status.BAD_REQUEST, 400, "Missing playUrl or queue")
                }
                queueList.add(
                    Episode(
                        seriesId = seriesId,
                        seriesTitle = seriesTitle,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeIndex = episodeIndex,
                        playUrl = playUrl,
                        duration = 0L,
                        headers = headersMap
                    )
                )
            } else {
                // Ensure intent level headers act as fallback
                if (headersMap.isNotEmpty()) {
                    for (i in queueList.indices) {
                        val item = queueList[i]
                        if (item.headers.isEmpty()) {
                            queueList[i] = item.copy(headers = headersMap)
                        }
                    }
                }
            }

            playerManager.playQueue(
                queue = queueList,
                startIndex = episodeIndex,
                startPositionMs = positionMs,
                autoPlayNext = autoPlayNext
            )

            Log.i(TAG, "Successfully started playback for: $seriesTitle - $episodeTitle (Queue: ${queueList.size})")
            jsonResponse(Response.Status.OK, 200, "Playback dispatched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse play request: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, 500, "Internal error: ${e.message}")
        }
    }

    private fun handleControl(session: IHTTPSession): Response {
        val bodyStr = readBodyString(session)
        if (bodyStr.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, 400, "Empty payload")
        }

        return try {
            val json = JSONObject(bodyStr)
            val action = json.optString("action").uppercase()

            when (action) {
                "PAUSE" -> playerManager.pause()
                "RESUME" -> playerManager.resume()
                "STOP" -> playerManager.stop()
                "NEXT" -> playerManager.playNext()
                "PREV", "PREVIOUS" -> playerManager.playPrevious()
                "SEEK" -> {
                    val pos = json.optLong("positionMs", 0L)
                    playerManager.seekTo(pos)
                }
                "SPEED", "SET_SPEED" -> {
                    val speed = json.optDouble("speed", 1.0).toFloat()
                    playerManager.setPlaybackSpeed(speed)
                }
                "PLAY_EPISODE", "PLAYEPISODE" -> {
                    val idx = json.optInt("episodeIndex", 0)
                    val pos = json.optLong("positionMs", 0L)
                    playerManager.playEpisode(idx, pos)
                }
                else -> return jsonResponse(Response.Status.BAD_REQUEST, 400, "Unknown action: $action")
            }

            Log.i(TAG, "Control command executed: $action")
            jsonResponse(Response.Status.OK, 200, "Action $action executed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle control: ${e.message}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, 500, "Error: ${e.message}")
        }
    }

    private fun readBodyString(session: IHTTPSession): String {
        val lenStr = session.headers["content-length"]
        val len = lenStr?.toIntOrNull() ?: 0
        if (len > 0) {
            try {
                val buffer = ByteArray(len)
                var totalRead = 0
                while (totalRead < len) {
                    val read = session.inputStream.read(buffer, totalRead, len - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead > 0) {
                    return String(buffer, 0, totalRead, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading inputStream directly: ${e.message}")
            }
        }

        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
            val postData = files["content"] ?: files["postData"]
            if (!postData.isNullOrBlank()) {
                val file = File(postData)
                if (file.exists() && file.isFile) {
                    return file.readText(Charsets.UTF_8)
                }
                return postData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in parseBody: ${e.message}")
        } finally {
            files.values.forEach { filePath ->
                try {
                    val f = File(filePath)
                    if (f.exists() && f.isFile) f.delete()
                } catch (_: Exception) {}
            }
        }
        return ""
    }

    private fun jsonResponse(status: Response.Status, code: Int, message: String): Response {
        val obj = JSONObject().apply {
            put("code", code)
            put("success", code in 200..299)
            put("message", message)
        }
        return jsonResponse(status, obj)
    }

    private fun jsonResponse(status: Response.Status, obj: JSONObject): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, obj.toString())
        addCorsHeaders(resp)
        return resp
    }

    private fun addCorsHeaders(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "*")
    }
}
