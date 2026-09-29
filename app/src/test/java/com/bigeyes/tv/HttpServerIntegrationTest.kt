package com.bigeyes.tv

import com.bigeyes.tv.airplay.AirPlayHttpHandler
import com.bigeyes.tv.dlna.DlnaActionHandler
import com.bigeyes.tv.fakes.FakeDeviceIdentity
import com.bigeyes.tv.fakes.FakePlayback
import com.bigeyes.tv.fakes.FakeVolumeController
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the *real* [AirPlayHttpHandler] and [DlnaActionHandler] behind a real NanoHTTPD socket so
 * the routed HTTP contract is verified end to end instead of against a local copy of the logic.
 */
class HttpServerIntegrationTest {

    private var server: RealHandlerServer? = null
    private lateinit var player: FakePlayback
    private lateinit var identity: FakeDeviceIdentity
    private lateinit var volume: FakeVolumeController
    private val testPort = 17000

    @Before
    fun setUp() {
        player = FakePlayback()
        identity = FakeDeviceIdentity()
        volume = FakeVolumeController(percent = 40, mutedState = false)
        server = RealHandlerServer(
            port = testPort,
            player = player,
            identity = identity,
            volume = volume
        ).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL("http://127.0.0.1:$testPort$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        return conn
    }

    private fun HttpURLConnection.body(): String =
        (if (responseCode in 200..399) inputStream else errorStream)
            .readBytes().toString(Charsets.UTF_8)

    /**
     * Writes a fixed-length POST body. NanoHTTPD 2.3.1 cannot decode chunked request bodies, so
     * the length must be declared up front instead of letting [HttpURLConnection] choose.
     */
    private fun post(
        path: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        val conn = open(path, "POST")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", contentType)
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        conn.outputStream.use { it.write(body) }
        return conn
    }

    // ----------------------------------------------------------------- AirPlay

    @Test
    fun testGetServerInfo() {
        val conn = open("/server-info", "GET")
        assertEquals(200, conn.responseCode)
        assertEquals("text/x-apple-plist+xml", conn.contentType)
        val body = conn.body()
        assertTrue("Must contain deviceid", body.contains("<key>deviceid</key>"))
        assertTrue("Must advertise the fake device id", body.contains(identity.deviceId))
        assertTrue("Must contain AppleTV2,1", body.contains("<string>AppleTV2,1</string>"))
    }

    @Test
    fun testPostPlayWithBinaryPlist() {
        val dict = com.dd.plist.NSDictionary()
        dict.put("Content-Location", com.dd.plist.NSString("http://192.168.1.50:8765/stream/master.m3u8"))
        dict.put("Start-Position", com.dd.plist.NSNumber(15.0))
        val binaryBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)

        val conn = post(
            path = "/play",
            body = binaryBytes,
            contentType = "application/x-apple-binary-plist"
        )

        assertEquals(200, conn.responseCode)
        assertEquals(
            "play:http://192.168.1.50:8765/stream/master.m3u8:15000:",
            player.calls.single()
        )
    }

    @Test
    fun testPostPlayWithPlainText() {
        val bytes = "Content-Location: http://cdn.test.com/sample.mp4\nStart-Position: 0.0\n"
            .toByteArray(Charsets.UTF_8)

        val conn = post(path = "/play", body = bytes, contentType = "text/parameters")

        assertEquals(200, conn.responseCode)
        assertEquals("play:http://cdn.test.com/sample.mp4:0:", player.calls.single())
    }

    @Test
    fun testPostPlayWithInvalidPayloadIs400() {
        val conn = post(path = "/play", body = ByteArray(4), contentType = "application/x-apple-binary-plist")

        assertEquals(400, conn.responseCode)
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun testGetPlaybackInfo() {
        player.durationMillis = 3_600_000L
        player.positionMs = 125_000L
        player.playing = true

        val conn = open("/playback-info", "GET")
        assertEquals(200, conn.responseCode)
        assertEquals("text/x-apple-plist+xml", conn.contentType)
        val body = conn.body()
        assertTrue(body.contains("<key>duration</key>"))
        assertTrue(body.contains("<key>position</key>"))
        assertTrue(body.contains("<key>rate</key>"))
        assertTrue(body.contains("<real>1.000000</real>"))
    }

    @Test
    fun testPostRatePauseAndResume() {
        var conn = open("/rate?value=0.000000", "POST")
        assertEquals(200, conn.responseCode)
        assertEquals(listOf("pause"), player.calls)

        conn = open("/rate?value=1.000000", "POST")
        assertEquals(200, conn.responseCode)
        assertEquals(listOf("pause", "resume"), player.calls)
    }

    @Test
    fun testPostAndGetScrub() {
        var conn = open("/scrub?position=150.500000", "POST")
        assertEquals(200, conn.responseCode)
        assertEquals(listOf("seek:150500"), player.calls)

        player.positionMs = 150_500L
        conn = open("/scrub", "GET")
        assertEquals(200, conn.responseCode)
        assertEquals("text/parameters", conn.contentType)
        assertTrue(conn.body().contains("position: 150.500000"))
    }

    @Test
    fun testPostStop() {
        val conn = open("/stop", "POST")
        assertEquals(200, conn.responseCode)
        assertEquals(listOf("stop"), player.calls)
    }

    @Test
    fun testReverseChannelEchoesSessionId() {
        val conn = open("/reverse", "POST")
        conn.setRequestProperty("X-Apple-Session-ID", "session-abc")
        assertEquals(200, conn.responseCode)
        assertEquals("session-abc", conn.getHeaderField("X-Apple-Session-ID"))
        assertEquals("keep-alive", conn.getHeaderField("Connection"))
    }

    @Test
    fun testSetPropertyAppliesVolumeAndGetPropertyReadsItBack() {
        val dict = com.dd.plist.NSDictionary()
        dict.put("volume", com.dd.plist.NSNumber(0.25))
        val bytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)

        var conn = post(path = "/setProperty", body = bytes, contentType = "application/x-apple-binary-plist")
        assertEquals(200, conn.responseCode)
        assertEquals(listOf("setVolume:0.25"), player.calls)

        conn = open("/getProperty", "GET")
        assertEquals(200, conn.responseCode)
        assertEquals("text/x-apple-plist+xml", conn.contentType)
        assertTrue(conn.body().contains("<key>volume</key>"))
    }

    @Test
    fun testSlideshowFeaturesIsHonestAboutCapabilities() {
        val conn = open("/slideshow-features", "GET")
        assertEquals(200, conn.responseCode)
        assertEquals("text/x-apple-plist+xml", conn.contentType)
        val body = conn.body()
        assertTrue(body.contains("<key>supportsPhotoCaching</key>"))
        assertTrue(body.contains("<key>transitions</key>"))
        assertTrue(body.contains("<array/>"))
    }

    // -------------------------------------------------------------------- DLNA

    @Test
    fun testDlnaDescriptionOverSocket() {
        val conn = open("/description.xml", "GET")
        assertEquals(200, conn.responseCode)
        val body = conn.body()
        assertTrue(body.contains("<modelNumber>"))
        assertTrue(body.contains("<UDN>${identity.udn}</UDN>"))
        assertTrue(body.contains("/upnp/control/renderingcontrol"))
    }

    @Test
    fun testDlnaGetVolumeOverSocket() {
        volume.percent = 61
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body><u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
        <InstanceID>0</InstanceID><Channel>Master</Channel>
    </u:GetVolume></s:Body>
</s:Envelope>"""

        val conn = post(
            path = "/upnp/control/renderingcontrol",
            body = soap.toByteArray(Charsets.UTF_8),
            contentType = "text/xml; charset=\"utf-8\"",
            headers = mapOf(
                "SOAPAction" to "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\""
            )
        )

        assertEquals(200, conn.responseCode)
        assertTrue(conn.body().contains("<CurrentVolume>61</CurrentVolume>"))
    }

    @Test
    fun testDlnaUnknownActionOverSocketIsUpnpFault() {
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        <InstanceID>0</InstanceID>
    </u:Browse></s:Body>
</s:Envelope>"""

        val conn = post(
            path = "/upnp/control/avtransport",
            body = soap.toByteArray(Charsets.UTF_8),
            contentType = "text/xml; charset=\"utf-8\"",
            headers = mapOf(
                "SOAPAction" to "\"urn:schemas-upnp-org:service:AVTransport:1#Browse\""
            )
        )

        assertEquals(500, conn.responseCode)
        assertTrue(conn.body().contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun testUnknownRouteIs404() {
        assertEquals(404, open("/definitely-not-a-route", "GET").responseCode)
    }

    @Test
    fun testActualCurlCommandFlow() {
        val dict = com.dd.plist.NSDictionary()
        dict.put("Content-Location", com.dd.plist.NSString("http://192.168.1.188:8765/stream/cctv1.m3u8"))
        dict.put("Start-Position", com.dd.plist.NSNumber(0.0))
        val bplistBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)
        val bplistFile = File("build/tmp/sample_play.bplist")
        bplistFile.parentFile?.mkdirs()
        bplistFile.writeBytes(bplistBytes)

        fun runCurl(args: List<String>): String {
            val cmd = mutableListOf("curl.exe")
            cmd.addAll(args)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            return output
        }

        val infoOutput = runCurl(listOf("-s", "-i", "http://127.0.0.1:$testPort/server-info"))
        assertTrue(infoOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(infoOutput.contains("text/x-apple-plist+xml"))

        val playOutput = runCurl(
            listOf(
                "-s", "-i", "-X", "POST",
                "-H", "Content-Type: application/x-apple-binary-plist",
                "--data-binary", "@${bplistFile.absolutePath}",
                "http://127.0.0.1:$testPort/play"
            )
        )
        assertTrue(playOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(player.calls.any { it.startsWith("play:http://192.168.1.188:8765/stream/cctv1.m3u8") })

        val scrubOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/scrub?position=60.000000"))
        assertTrue(scrubOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(player.calls.contains("seek:60000"))

        val stopOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/stop"))
        assertTrue(stopOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(player.calls.contains("stop"))
    }

    /** Mirrors [com.bigeyes.tv.server.TvHttpServer]'s routing against the real handlers. */
    class RealHandlerServer(
        port: Int,
        player: FakePlayback,
        identity: FakeDeviceIdentity,
        volume: FakeVolumeController
    ) : NanoHTTPD(port) {

        private val airPlay = AirPlayHttpHandler(player, identity)
        private val dlna = DlnaActionHandler(player, identity, volume, port)

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                airPlay.canHandle(uri) -> airPlay.handleRequest(session)
                dlna.canHandle(uri) -> dlna.handleRequest(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }
}
