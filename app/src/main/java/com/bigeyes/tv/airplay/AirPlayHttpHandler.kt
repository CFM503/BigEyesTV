package com.bigeyes.tv.airplay

import android.content.Context
import android.util.Log
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.PlistHelper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.HashMap

/**
 * Handles AirPlay Video Playback HTTP endpoints in NanoHTTPD.
 */
class AirPlayHttpHandler(
    private val context: Context,
    private val playerManager: TvPlayerManager
) {
    private val deviceIdManager = DeviceIdManager.getInstance(context)

    fun canHandle(uri: String): Boolean {
        return uri == "/server-info" ||
                uri == "/play" ||
                uri == "/playback-info" ||
                uri == "/rate" ||
                uri == "/scrub" ||
                uri == "/stop" ||
                uri == "/reverse" ||
                uri == "/setProperty" ||
                uri == "/getProperty" ||
                uri == "/slideshow-features"
    }

    fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "AirPlay HTTP request: $method $uri, parms=${session.parms}")

        return when (uri) {
            "/server-info" -> handleServerInfo(session)
            "/play" -> handlePlay(session)
            "/playback-info" -> handlePlaybackInfo(session)
            "/rate" -> handleRate(session)
            "/scrub" -> handleScrub(session)
            "/stop" -> handleStop(session)
            "/reverse" -> handleReverse(session)
            "/setProperty", "/getProperty", "/slideshow-features" -> handleAuxiliary(session)
            else -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not Found"
            )
        }
    }

    /**
     * GET /server-info
     */
    private fun handleServerInfo(session: IHTTPSession): Response {
        val xml = PlistHelper.generateServerInfoXml(
            deviceId = deviceIdManager.deviceId,
            model = "AppleTV2,1",
            features = 7L,
            srcvers = "130.14",
            protovers = "1.0"
        )
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            MIME_APPLE_PLIST,
            xml
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    /**
     * POST /play
     */
    private fun handlePlay(session: IHTTPSession): Response {
        val bodyBytes = extractBodyBytes(session)
        val contentType = session.headers["content-type"]

        val playRequest = PlistHelper.parsePlayRequest(bodyBytes, contentType)
        if (playRequest != null && playRequest.contentLocation.isNotBlank()) {
            val url = playRequest.contentLocation
            val startPositionSec = playRequest.startPosition
            val startPositionMs = (startPositionSec * 1000).toLong()

            Log.i(TAG, "AirPlay /play accepted: url=$url, startPositionSec=$startPositionSec")
            playerManager.play(url, startPositionMs)

            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            )
            response.addHeader("Server", "AirTunes/130.14")
            return response
        } else {
            Log.e(TAG, "Failed to parse /play request: bodySize=${bodyBytes.size}, type=$contentType")
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                NanoHTTPD.MIME_PLAINTEXT,
                "Invalid play payload"
            )
        }
    }

    /**
     * GET /playback-info
     */
    private fun handlePlaybackInfo(session: IHTTPSession): Response {
        val durationSec = playerManager.getDurationMs() / 1000.0
        val positionSec = playerManager.getCurrentPositionMs() / 1000.0
        val isPlaying = playerManager.isPlaying()
        val isReady = playerManager.isReady()

        val xml = PlistHelper.generatePlaybackInfoXml(
            durationSec = durationSec,
            positionSec = positionSec,
            isPlaying = isPlaying,
            isReady = isReady
        )

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            MIME_APPLE_PLIST,
            xml
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    /**
     * POST /rate?value=[float]
     */
    private fun handleRate(session: IHTTPSession): Response {
        val valueStr = session.parms["value"]
        val value = valueStr?.toDoubleOrNull() ?: 1.0
        Log.i(TAG, "AirPlay /rate request: value=$value")

        if (value == 0.0) {
            playerManager.pause()
        } else {
            playerManager.resume()
        }

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            NanoHTTPD.MIME_PLAINTEXT,
            ""
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    /**
     * POST /scrub?position=[float] or GET /scrub
     */
    private fun handleScrub(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            val durationSec = playerManager.getDurationMs() / 1000.0
            val positionSec = playerManager.getCurrentPositionMs() / 1000.0
            val text = PlistHelper.generateScrubText(durationSec, positionSec)
            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                MIME_TEXT_PARAMETERS,
                text
            )
            response.addHeader("Server", "AirTunes/130.14")
            return response
        } else {
            val posStr = session.parms["position"]
            val positionSec = posStr?.toDoubleOrNull() ?: 0.0
            val positionMs = (positionSec * 1000).toLong()
            Log.i(TAG, "AirPlay POST /scrub request: positionSec=$positionSec ($positionMs ms)")

            playerManager.seekTo(positionMs)

            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            )
            response.addHeader("Server", "AirTunes/130.14")
            return response
        }
    }

    /**
     * POST /stop
     */
    private fun handleStop(session: IHTTPSession): Response {
        Log.i(TAG, "AirPlay /stop request received")
        playerManager.stop()

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            NanoHTTPD.MIME_PLAINTEXT,
            ""
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    /**
     * POST /reverse
     */
    private fun handleReverse(session: IHTTPSession): Response {
        // Reverse connection not needed for video streaming
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            NanoHTTPD.MIME_PLAINTEXT,
            ""
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    /**
     * POST /setProperty, GET /getProperty, /slideshow-features
     */
    private fun handleAuxiliary(session: IHTTPSession): Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            NanoHTTPD.MIME_PLAINTEXT,
            ""
        )
        response.addHeader("Server", "AirTunes/130.14")
        return response
    }

    private fun extractBodyBytes(session: IHTTPSession): ByteArray {
        // 1. If Content-Length header is present, read raw bytes directly from inputStream
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
                    return buffer.copyOf(totalRead)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading inputStream: ${e.message}")
            }
        }

        // 2. Fallback to parseBody for form-urlencoded or temp files
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            Log.w(TAG, "Error in parseBody: ${e.message}")
        }

        for ((_, value) in files) {
            if (!value.isNullOrBlank()) {
                val tempFile = File(value)
                if (tempFile.exists() && tempFile.isFile) {
                    try {
                        val bytes = tempFile.readBytes()
                        if (bytes.isNotEmpty()) return bytes
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        val postData = files["content"] ?: files["postData"]
        if (!postData.isNullOrBlank()) {
            return postData.toByteArray(Charsets.UTF_8)
        }

        return ByteArray(0)
    }

    companion object {
        private const val TAG = "AirPlayHttpHandler"
        private const val MIME_APPLE_PLIST = "text/x-apple-plist+xml"
        private const val MIME_TEXT_PARAMETERS = "text/parameters"
    }
}
