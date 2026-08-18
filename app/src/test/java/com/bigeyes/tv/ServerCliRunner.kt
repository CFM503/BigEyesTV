package com.bigeyes.tv

import com.bigeyes.tv.utils.PlistHelper
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.HashMap

fun main(args: Array<String>) {
    val port = if (args.isNotEmpty()) args[0].toInt() else 7000
    val server = object : NanoHTTPD(port) {
        var currentUrl: String? = null
        var isPlaying = false
        var currentPos = 0.0

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            println("[SERVER] Received ${session.method} $uri, headers=${session.headers}")

            return when (uri) {
                "/server-info" -> {
                    val xml = PlistHelper.generateServerInfoXml(
                        deviceId = "58:55:CA:1A:E2:88",
                        model = "AppleTV2,1",
                        features = 7L,
                        srcvers = "130.14",
                        protovers = "1.0"
                    )
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", xml)
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/play" -> {
                    var bytes: ByteArray? = null
                    val lenStr = session.headers["content-length"]
                    val len = lenStr?.toIntOrNull() ?: 0
                    if (len > 0) {
                        val buffer = ByteArray(len)
                        var totalRead = 0
                        while (totalRead < len) {
                            val r = session.inputStream.read(buffer, totalRead, len - totalRead)
                            if (r == -1) break
                            totalRead += r
                        }
                        if (totalRead > 0) bytes = buffer.copyOf(totalRead)
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        val files = HashMap<String, String>()
                        try { session.parseBody(files) } catch (e: Exception) {}
                        for ((_, value) in files) {
                            if (!value.isNullOrBlank()) {
                                val tempFile = File(value)
                                if (tempFile.exists() && tempFile.isFile) {
                                    bytes = tempFile.readBytes()
                                    if (bytes.isNotEmpty()) break
                                }
                            }
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            val postData = files["content"] ?: files["postData"] ?: ""
                            bytes = postData.toByteArray(Charsets.UTF_8)
                        }
                    }

                    val req = PlistHelper.parsePlayRequest(bytes ?: ByteArray(0), session.headers["content-type"])
                    if (req != null) {
                        currentUrl = req.contentLocation
                        currentPos = req.startPosition
                        isPlaying = true
                        println("[SERVER] >> PlayerManager.play(url='${req.contentLocation}', startPos=${req.startPosition})")
                        val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request")
                    }
                }
                "/playback-info" -> {
                    val xml = PlistHelper.generatePlaybackInfoXml(
                        durationSec = 3600.0,
                        positionSec = currentPos,
                        isPlaying = isPlaying,
                        isReady = true
                    )
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", xml)
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/rate" -> {
                    val valStr = session.parms["value"] ?: "1.0"
                    val v = valStr.toDoubleOrNull() ?: 1.0
                    isPlaying = (v != 0.0)
                    println("[SERVER] >> PlayerManager.${if (isPlaying) "resume()" else "pause()"}")
                    val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/scrub" -> {
                    if (session.method == Method.GET) {
                        val text = PlistHelper.generateScrubText(3600.0, currentPos)
                        val resp = newFixedLengthResponse(Response.Status.OK, "text/parameters", text)
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    } else {
                        val posStr = session.parms["position"] ?: "0.0"
                        currentPos = posStr.toDoubleOrNull() ?: 0.0
                        println("[SERVER] >> PlayerManager.seekTo($currentPos sec)")
                        val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    }
                }
                "/stop" -> {
                    isPlaying = false
                    currentUrl = null
                    println("[SERVER] >> PlayerManager.stop()")
                    val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/reverse" -> {
                    val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    server.start(5000, false)
    println("AirPlay Test Server is running on port $port. Press enter to exit.")
    readLine()
    server.stop()
}
