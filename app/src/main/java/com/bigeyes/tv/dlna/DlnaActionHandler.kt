package com.bigeyes.tv.dlna

import android.content.Context
import android.util.Log
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.util.HashMap
import java.util.Locale

/**
 * Handles DLNA / UPnP MediaRenderer XML descriptions and SOAP control endpoints.
 */
class DlnaActionHandler(
    private val context: Context,
    private val playerManager: TvPlayerManager,
    private val port: Int = 7000
) {
    private val deviceIdManager = DeviceIdManager.getInstance(context)

    fun canHandle(uri: String): Boolean {
        return uri == "/description.xml" ||
                uri == "/avtransport.xml" ||
                uri == "/renderingcontrol.xml" ||
                uri == "/connectionmanager.xml" ||
                uri.startsWith("/upnp/control/")
    }

    fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "DLNA HTTP request: ${session.method} $uri")

        return when (uri) {
            "/description.xml" -> handleDeviceDescription(session)
            "/avtransport.xml" -> handleAvTransportScpd()
            "/renderingcontrol.xml" -> handleRenderingControlScpd()
            "/connectionmanager.xml" -> handleConnectionManagerScpd()
            "/upnp/control/avtransport" -> handleAvTransportControl(session)
            "/upnp/control/renderingcontrol" -> handleRenderingControl(session)
            "/upnp/control/connectionmanager" -> handleConnectionManager(session)
            else -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not Found"
            )
        }
    }

    private fun handleDeviceDescription(session: IHTTPSession): Response {
        val hostIp = NetworkUtils.getLocalIpAddress()
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion>
        <major>1</major>
        <minor>0</minor>
    </specVersion>
    <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>${deviceIdManager.deviceName}</friendlyName>
        <manufacturer>BigEyes</manufacturer>
        <manufacturerURL>https://github.com/bigeyes-tv</manufacturerURL>
        <modelDescription>BigEyes TV Wireless Media Renderer</modelDescription>
        <modelName>BigEyes TV</modelName>
        <modelNumber>0.1.0</modelNumber>
        <modelURL>https://github.com/bigeyes-tv</modelURL>
        <serialNumber>${deviceIdManager.deviceId}</serialNumber>
        <UDN>${deviceIdManager.udn}</UDN>
        <serviceList>
            <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/avtransport</controlURL>
                <eventSubURL>/upnp/event/avtransport</eventSubURL>
                <SCPDURL>/avtransport.xml</SCPDURL>
            </service>
            <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/upnp/control/renderingcontrol</controlURL>
                <eventSubURL>/upnp/event/renderingcontrol</eventSubURL>
                <SCPDURL>/renderingcontrol.xml</SCPDURL>
            </service>
            <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <controlURL>/upnp/control/connectionmanager</controlURL>
                <eventSubURL>/upnp/event/connectionmanager</eventSubURL>
                <SCPDURL>/connectionmanager.xml</SCPDURL>
            </service>
        </serviceList>
    </device>
</root>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun handleAvTransportScpd(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <actionList>
        <action><name>SetAVTransportURI</name></action>
        <action><name>Play</name></action>
        <action><name>Pause</name></action>
        <action><name>Seek</name></action>
        <action><name>Stop</name></action>
        <action><name>GetPositionInfo</name></action>
        <action><name>GetTransportInfo</name></action>
    </actionList>
</scpd>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun handleRenderingControlScpd(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <actionList>
        <action><name>GetVolume</name></action>
        <action><name>SetVolume</name></action>
        <action><name>GetMute</name></action>
        <action><name>SetMute</name></action>
    </actionList>
</scpd>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun handleConnectionManagerScpd(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <actionList>
        <action><name>GetProtocolInfo</name></action>
    </actionList>
</scpd>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun handleAvTransportControl(session: IHTTPSession): Response {
        val body = extractBodyString(session)
        val soapAction = session.headers["soapaction"] ?: ""
        Log.i(TAG, "DLNA AVTransport SOAPAction: $soapAction")

        return when {
            soapAction.contains("SetAVTransportURI") || body.contains("SetAVTransportURI") -> {
                val uriRegex = Regex("<CurrentURI>(.*?)</CurrentURI>", RegexOption.DOT_MATCHES_ALL)
                val match = uriRegex.find(body)
                val streamUrl = match?.groupValues?.get(1)?.trim()?.replace("&amp;", "&") ?: ""
                Log.i(TAG, "DLNA SetAVTransportURI extracted URL: $streamUrl")
                if (streamUrl.isNotBlank()) {
                    playerManager.play(streamUrl, 0L)
                }
                buildSoapResponse("SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
            soapAction.contains("Play") || body.contains("<u:Play") || body.contains("<Play") -> {
                playerManager.resume()
                buildSoapResponse("PlayResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
            soapAction.contains("Pause") || body.contains("<u:Pause") || body.contains("<Pause") -> {
                playerManager.pause()
                buildSoapResponse("PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
            soapAction.contains("Seek") || body.contains("<u:Seek") || body.contains("<Seek") -> {
                val targetRegex = Regex("<Target>(.*?)</Target>")
                val targetStr = targetRegex.find(body)?.groupValues?.get(1)?.trim() ?: "00:00:00"
                val positionMs = parseTimeStringToMs(targetStr)
                Log.i(TAG, "DLNA Seek to $targetStr ($positionMs ms)")
                playerManager.seekTo(positionMs)
                buildSoapResponse("SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
            soapAction.contains("Stop") || body.contains("<u:Stop") || body.contains("<Stop") -> {
                playerManager.stop()
                buildSoapResponse("StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
            soapAction.contains("GetPositionInfo") || body.contains("GetPositionInfo") -> {
                val durationSec = playerManager.getDurationMs() / 1000
                val posSec = playerManager.getCurrentPositionMs() / 1000
                val durFormatted = formatSecondsToTimeString(durationSec)
                val posFormatted = formatSecondsToTimeString(posSec)
                val url = playerManager.currentUrl ?: ""

                val innerXml = """
                    <Track>1</Track>
                    <TrackDuration>$durFormatted</TrackDuration>
                    <TrackMetaData></TrackMetaData>
                    <TrackURI>$url</TrackURI>
                    <RelTime>$posFormatted</RelTime>
                    <AbsTime>$posFormatted</AbsTime>
                    <RelCount>2147483647</RelCount>
                    <AbsCount>2147483647</AbsCount>
                """.trimIndent()
                buildSoapResponse("GetPositionInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", innerXml)
            }
            soapAction.contains("GetTransportInfo") || body.contains("GetTransportInfo") -> {
                val state = if (playerManager.isPlaying()) "PLAYING" else if (playerManager.currentUrl != null) "PAUSED_PLAYBACK" else "STOPPED"
                val innerXml = """
                    <CurrentTransportState>$state</CurrentTransportState>
                    <CurrentTransportStatus>OK</CurrentTransportStatus>
                    <CurrentSpeed>1</CurrentSpeed>
                """.trimIndent()
                buildSoapResponse("GetTransportInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", innerXml)
            }
            else -> buildSoapResponse("GenericResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    private fun handleRenderingControl(session: IHTTPSession): Response {
        val innerXml = """<CurrentVolume>50</CurrentVolume><CurrentMute>0</CurrentMute>"""
        return buildSoapResponse("RenderingControlResponse", "urn:schemas-upnp-org:service:RenderingControl:1", innerXml)
    }

    private fun handleConnectionManager(session: IHTTPSession): Response {
        val innerXml = """<Source>http-get:*:video/mp4:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:*</Source><Sink></Sink>"""
        return buildSoapResponse("GetProtocolInfoResponse", "urn:schemas-upnp-org:service:ConnectionManager:1", innerXml)
    }

    private fun buildSoapResponse(actionResponse: String, serviceType: String, content: String): Response {
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:$actionResponse xmlns:u="$serviceType">
            $content
        </u:$actionResponse>
    </s:Body>
</s:Envelope>"""
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
        resp.addHeader("EXT", "")
        return resp
    }

    private fun extractBodyString(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            Log.w(TAG, "parseBody error: ${e.message}")
        }
        val postData = files["postData"]
        if (!postData.isNullOrBlank()) {
            val file = File(postData)
            if (file.exists() && file.isFile) {
                return try { file.readText(Charsets.UTF_8) } catch (e: Exception) { postData }
            }
            return postData
        }
        return ""
    }

    private fun parseTimeStringToMs(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 3) {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].split(".")
                val s = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].take(3).padEnd(3, '0').toLongOrNull() ?: 0L else 0L
                (h * 3600 + m * 60 + s) * 1000 + ms
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatSecondsToTimeString(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        private const val TAG = "DlnaActionHandler"
    }
}
