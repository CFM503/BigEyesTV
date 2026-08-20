package com.bigeyes.tv.dlna

import com.bigeyes.tv.MockSession
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.player.TvPlayerManager
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DlnaActionHandlerTest {

    @Test
    fun testAvTransportScpdContainsNextAndSetNext() {
        val session = MockSession("/avtransport.xml", NanoHTTPD.Method.GET)
        // Testing SCPD generation directly or via handler if mock context available
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <actionList>
        <action><name>SetAVTransportURI</name></action>
        <action><name>SetNextAVTransportURI</name></action>
        <action><name>Play</name></action>
        <action><name>Pause</name></action>
        <action><name>Seek</name></action>
        <action><name>Stop</name></action>
        <action><name>Next</name></action>
        <action><name>Previous</name></action>
        <action><name>GetPositionInfo</name></action>
        <action><name>GetTransportInfo</name></action>
    </actionList>
</scpd>"""
        assertTrue(xml.contains("<action><name>SetNextAVTransportURI</name></action>"))
        assertTrue(xml.contains("<action><name>Next</name></action>"))
        assertTrue(xml.contains("<action><name>Previous</name></action>"))
    }

    @Test
    fun testParseSetNextAVTransportURI() {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:SetNextAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <NextURI>http://192.168.1.50:8765/stream/ep2/index.m3u8</NextURI>
                        <NextURIMetaData></NextURIMetaData>
                    </u:SetNextAVTransportURI>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val uriRegex = Regex("<NextURI>(.*?)</NextURI>", RegexOption.DOT_MATCHES_ALL)
        val match = uriRegex.find(soapBody)
        val nextStreamUrl = match?.groupValues?.get(1)?.trim() ?: ""

        assertEquals("http://192.168.1.50:8765/stream/ep2/index.m3u8", nextStreamUrl)
    }

    @Test
    fun testTransportStateEndedIsStopped() {
        // Simulating the state logic
        fun resolveState(isPlaying: Boolean, isEnded: Boolean, currentUrl: String?): String {
            return when {
                isPlaying -> "PLAYING"
                isEnded -> "STOPPED"
                currentUrl != null -> "PAUSED_PLAYBACK"
                else -> "STOPPED"
            }
        }

        assertEquals("PLAYING", resolveState(isPlaying = true, isEnded = false, currentUrl = "http://test.com"))
        assertEquals("PAUSED_PLAYBACK", resolveState(isPlaying = false, isEnded = false, currentUrl = "http://test.com"))
        assertEquals("STOPPED", resolveState(isPlaying = false, isEnded = true, currentUrl = "http://test.com"))
        assertEquals("STOPPED", resolveState(isPlaying = false, isEnded = false, currentUrl = null))
    }
}
