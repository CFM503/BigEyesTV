package com.bigeyes.tv.server

import android.content.Context
import android.util.Log
import com.bigeyes.tv.airplay.AirPlayHttpHandler
import com.bigeyes.tv.dlna.DlnaActionHandler
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.utils.AudioManagerVolumeController
import com.bigeyes.tv.utils.DeviceIdManager
import fi.iki.elonen.NanoHTTPD

/**
 * Unified NanoHTTPD embedded server for BigEyes-TV.
 * Routes AirPlay and DLNA HTTP requests to their respective handlers on port 7000.
 */
class TvHttpServer(
    context: Context,
    playerManager: TvPlayerManager,
    port: Int = 7000
) : NanoHTTPD(port) {

    private val deviceIdentity = DeviceIdManager.getInstance(context)
    private val volumeController = AudioManagerVolumeController(context)
    private val airPlayHandler = AirPlayHttpHandler(playerManager, deviceIdentity)
    private val dlnaHandler = DlnaActionHandler(playerManager, deviceIdentity, volumeController, port)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Incoming HTTP ${session.method} $uri from ${session.remoteIpAddress}")

        return when {
            airPlayHandler.canHandle(uri) -> {
                airPlayHandler.handleRequest(session)
            }
            dlnaHandler.canHandle(uri) -> {
                dlnaHandler.handleRequest(session)
            }
            else -> {
                Log.w(TAG, "Unhandled HTTP route: $uri")
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "BigEyes-TV: Route not found: $uri"
                )
            }
        }
    }

    companion object {
        private const val TAG = "TvHttpServer"
    }
}
