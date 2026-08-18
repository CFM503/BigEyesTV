package com.bigeyes.tv

import com.bigeyes.tv.utils.PlistHelper
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlistHelperTest {

    @Test
    fun testParsePlainTextPlayRequest() {
        val bodyText = """
            Content-Location: http://192.168.1.100:8765/stream/test.m3u8
            Start-Position: 0.250000
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val request = PlistHelper.parsePlayRequest(bodyText, "text/parameters")
        assertNotNull("Request should not be null", request)
        assertEquals("http://192.168.1.100:8765/stream/test.m3u8", request?.contentLocation)
        assertEquals(0.25, request?.startPosition ?: 0.0, 0.001)
    }

    @Test
    fun testParseBinaryPlistPlayRequest() {
        // Construct a real binary plist using dd-plist
        val dict = NSDictionary()
        dict.put("Content-Location", NSString("http://example.com/live/stream.m3u8?token=abc&sign=123"))
        dict.put("Start-Position", NSNumber(45.5))

        val binaryBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)

        // Verify it starts with bplist00 magic bytes
        val magic = String(binaryBytes.take(8).toByteArray(), Charsets.US_ASCII)
        assertTrue("Should start with bplist00, but was: $magic", magic.startsWith("bplist"))

        val parsed = PlistHelper.parsePlayRequest(binaryBytes, "application/x-apple-binary-plist")
        assertNotNull("Parsed binary plist request should not be null", parsed)
        assertEquals("http://example.com/live/stream.m3u8?token=abc&sign=123", parsed?.contentLocation)
        assertEquals(45.5, parsed?.startPosition ?: 0.0, 0.001)
    }

    @Test
    fun testParseXmlPlistPlayRequest() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Content-Location</key>
	<string>http://movie.sample.com/video.mp4</string>
	<key>Start-Position</key>
	<real>120.000000</real>
</dict>
</plist>""".toByteArray(Charsets.UTF_8)

        val parsed = PlistHelper.parsePlayRequest(xml, "application/x-apple-plist+xml")
        assertNotNull("Parsed XML plist request should not be null", parsed)
        assertEquals("http://movie.sample.com/video.mp4", parsed?.contentLocation)
        assertEquals(120.0, parsed?.startPosition ?: 0.0, 0.001)
    }

    @Test
    fun testParseEmptyAndMalformedRequests() {
        assertNull(PlistHelper.parsePlayRequest(ByteArray(0), null))
        assertNull(PlistHelper.parsePlayRequest("random invalid data".toByteArray(), null))
    }

    @Test
    fun testGenerateServerInfoXml() {
        val deviceId = "58:55:CA:1A:E2:88"
        val xml = PlistHelper.generateServerInfoXml(deviceId = deviceId)

        assertTrue(xml.contains("<key>deviceid</key>\n\t<string>58:55:CA:1A:E2:88</string>"))
        assertTrue(xml.contains("<key>features</key>\n\t<integer>7</integer>"))
        assertTrue(xml.contains("<key>model</key>\n\t<string>AppleTV2,1</string>"))
        assertTrue(xml.contains("<key>protovers</key>\n\t<string>1.0</string>"))
        assertTrue(xml.contains("<key>srcvers</key>\n\t<string>130.14</string>"))

        // Verify that dd-plist can parse back our generated XML
        val parsed = PropertyListParser.parse(xml.toByteArray(Charsets.UTF_8)) as NSDictionary
        assertEquals(deviceId, (parsed.objectForKey("deviceid") as NSString).content)
        assertEquals(7L, (parsed.objectForKey("features") as NSNumber).longValue())
        assertEquals("AppleTV2,1", (parsed.objectForKey("model") as NSString).content)
    }

    @Test
    fun testGeneratePlaybackInfoXml() {
        val xml = PlistHelper.generatePlaybackInfoXml(
            durationSec = 3600.0,
            positionSec = 150.5,
            isPlaying = true,
            isReady = true
        )

        assertTrue(xml.contains("<key>duration</key>\n\t<real>3600.000000</real>"))
        assertTrue(xml.contains("<key>position</key>\n\t<real>150.500000</real>"))
        assertTrue(xml.contains("<key>rate</key>\n\t<real>1.000000</real>"))
        assertTrue(xml.contains("<key>readyToPlay</key>\n\t<true/>"))

        // Verify that dd-plist can parse back our generated XML
        val parsed = PropertyListParser.parse(xml.toByteArray(Charsets.UTF_8)) as NSDictionary
        assertEquals(3600.0, (parsed.objectForKey("duration") as NSNumber).doubleValue(), 0.001)
        assertEquals(150.5, (parsed.objectForKey("position") as NSNumber).doubleValue(), 0.001)
        assertEquals(1.0, (parsed.objectForKey("rate") as NSNumber).doubleValue(), 0.001)
    }

    @Test
    fun testGenerateScrubText() {
        val scrub = PlistHelper.generateScrubText(120.0, 45.5)
        assertEquals("duration: 120.000000\nposition: 45.500000\n", scrub)
    }
}
