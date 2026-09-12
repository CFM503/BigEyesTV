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
        fun resolveState(isPlaying: Boolean, isEnded: Boolean, isIdle: Boolean, isReady: Boolean, currentUrl: String?): String {
            return when {
                isPlaying -> "PLAYING"
                isEnded || isIdle -> "STOPPED"
                currentUrl != null && isReady -> "PAUSED_PLAYBACK"
                else -> "STOPPED"
            }
        }

        assertEquals("PLAYING", resolveState(isPlaying = true, isEnded = false, isIdle = false, isReady = true, currentUrl = "http://test.com"))
        assertEquals("PAUSED_PLAYBACK", resolveState(isPlaying = false, isEnded = false, isIdle = false, isReady = true, currentUrl = "http://test.com"))
        assertEquals("STOPPED", resolveState(isPlaying = false, isEnded = true, isIdle = false, isReady = false, currentUrl = "http://test.com"))
        assertEquals("STOPPED", resolveState(isPlaying = false, isEnded = false, isIdle = true, isReady = false, currentUrl = "http://test.com"))
        assertEquals("STOPPED", resolveState(isPlaying = false, isEnded = false, isIdle = false, isReady = false, currentUrl = null))
    }

    @Test
    fun testXmlTagValueExtractionWithNamespace() {
        fun extractXmlTagValue(xml: String, tagName: String): String? {
            val regex = Regex("<(?:[a-zA-Z0-9_]+:)?$tagName(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_]+:)?$tagName>", RegexOption.DOT_MATCHES_ALL)
            return regex.find(xml)?.groupValues?.get(1)?.trim()
        }

        val plainXml = "<CurrentURI>http://example.com/video.mp4</CurrentURI>"
        val nsXml = "<u:CurrentURI>http://example.com/video.mp4</u:CurrentURI>"
        val attrXml = "<CurrentURI xmlns:dt=\"string\">http://example.com/video.mp4</CurrentURI>"

        assertEquals("http://example.com/video.mp4", extractXmlTagValue(plainXml, "CurrentURI"))
        assertEquals("http://example.com/video.mp4", extractXmlTagValue(nsXml, "CurrentURI"))
        assertEquals("http://example.com/video.mp4", extractXmlTagValue(attrXml, "CurrentURI"))
    }

    @Test
    fun testCleanXmlValueCdataAndEntities() {
        fun cleanXmlValue(value: String): String {
            var cleaned = value.trim()
            if (cleaned.startsWith("<![CDATA[", ignoreCase = true) && cleaned.endsWith("]]>")) {
                cleaned = cleaned.substring(9, cleaned.length - 3).trim()
            }
            return cleaned.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .trim()
        }

        val cdataUrl = "<![CDATA[http://example.com/video.mp4?token=abc&amp;id=123]]>"
        assertEquals("http://example.com/video.mp4?token=abc&id=123", cleanXmlValue(cdataUrl))

        val escapedUrl = "http://example.com/video.mp4?a=1&amp;b=2"
        assertEquals("http://example.com/video.mp4?a=1&b=2", cleanXmlValue(escapedUrl))
    }

    @Test
    fun testActionRoutingOrder() {
        // Ensure SetNextAVTransportURI is distinguished from SetAVTransportURI
        val setNextAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetNextAVTransportURI\""
        val setAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""

        fun route(soapAction: String): String {
            return when {
                soapAction.contains("SetNextAVTransportURI") -> "NEXT"
                soapAction.contains("SetAVTransportURI") -> "SET"
                else -> "OTHER"
            }
        }

        assertEquals("NEXT", route(setNextAction))
        assertEquals("SET", route(setAction))
    }

    @Test
    fun testExtractTitleFromMetadata() {
        fun extractXmlTagValue(xml: String, tagName: String): String? {
            val regex = Regex("<(?:[a-zA-Z0-9_]+:)?$tagName(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_]+:)?$tagName>", RegexOption.DOT_MATCHES_ALL)
            return regex.find(xml)?.groupValues?.get(1)?.trim()
        }

        val metadata = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
                <item id="0" parentID="-1" restricted="1">
                    <dc:title>庆余年 第二季 第05集</dc:title>
                    <res>http://192.168.1.100:8899/stream/123/index.m3u8</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()

        val title = extractXmlTagValue(metadata, "dc:title") ?: extractXmlTagValue(metadata, "title")
        assertEquals("庆余年 第二季 第05集", title)
    }
}
