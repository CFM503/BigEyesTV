package com.bigeyes.tv

import com.bigeyes.tv.utils.PlistHelper
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Standalone integration test running NanoHTTPD with AirPlay logic
 * and testing HTTP requests with real socket connections.
 */
class HttpServerIntegrationTest {

    private var server: TestAirPlayServer? = null
    private val testPort = 17000

    @Before
    fun setUp() {
        server = TestAirPlayServer(testPort).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun testGetServerInfo() {
        val conn = URL("http://127.0.0.1:$testPort/server-info").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connect()

        assertEquals(200, conn.responseCode)
        assertEquals("text/x-apple-plist+xml", conn.contentType)
        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("Must contain deviceid", body.contains("<key>deviceid</key>"))
        assertTrue("Must contain features 7", body.contains("<key>features</key>\n\t<integer>7</integer>"))
        assertTrue("Must contain AppleTV2,1", body.contains("<key>model</key>\n\t<string>AppleTV2,1</string>"))
    }

    @Test
    fun testPostPlayWithBinaryPlist() {
        val dict = NSDictionary()
        dict.put("Content-Location", NSString("http://192.168.1.50:8765/stream/master.m3u8"))
        dict.put("Start-Position", NSNumber(15.2))
        val binaryBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)

        val conn = URL("http://127.0.0.1:$testPort/play").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-apple-binary-plist")
        conn.setRequestProperty("Content-Length", binaryBytes.size.toString())

        conn.outputStream.use { it.write(binaryBytes) }

        assertEquals(200, conn.responseCode)
        assertEquals("http://192.168.1.50:8765/stream/master.m3u8", server?.lastPlayedUrl)
        assertEquals(15.2, server?.lastStartPosition ?: 0.0, 0.01)
    }

    @Test
    fun testPostPlayWithPlainText() {
        val textBody = "Content-Location: http://cdn.test.com/sample.mp4\nStart-Position: 0.0\n"
        val bytes = textBody.toByteArray(Charsets.UTF_8)

        val conn = URL("http://127.0.0.1:$testPort/play").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/parameters")
        conn.outputStream.use { it.write(bytes) }

        assertEquals(200, conn.responseCode)
        assertEquals("http://cdn.test.com/sample.mp4", server?.lastPlayedUrl)
    }

    @Test
    fun testGetPlaybackInfo() {
        server?.mockDurationSec = 3600.0
        server?.mockPositionSec = 125.0
        server?.mockIsPlaying = true

        val conn = URL("http://127.0.0.1:$testPort/playback-info").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connect()

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("Must contain duration", body.contains("<key>duration</key>"))
        assertTrue("Must contain position", body.contains("<key>position</key>"))
        assertTrue("Must contain rate 1.0", body.contains("<key>rate</key>\n\t<real>1.000000</real>"))
    }

    @Test
    fun testPostRatePauseAndResume() {
        // Test Pause (value=0.0)
        var conn = URL("http://127.0.0.1:$testPort/rate?value=0.000000").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        assertEquals(200, conn.responseCode)
        assertEquals(false, server?.mockIsPlaying)

        // Test Resume (value=1.0)
        conn = URL("http://127.0.0.1:$testPort/rate?value=1.000000").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        assertEquals(200, conn.responseCode)
        assertEquals(true, server?.mockIsPlaying)
    }

    @Test
    fun testPostAndGetScrub() {
        // Test POST /scrub
        var conn = URL("http://127.0.0.1:$testPort/scrub?position=150.500000").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        assertEquals(200, conn.responseCode)
        assertEquals(150.5, server?.mockPositionSec ?: 0.0, 0.01)

        // Test GET /scrub
        conn = URL("http://127.0.0.1:$testPort/scrub").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val scrubText = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("Must contain position: 150.5", scrubText.contains("position: 150.500000"))
    }

    @Test
    fun testPostStop() {
        val conn = URL("http://127.0.0.1:$testPort/stop").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        assertEquals(200, conn.responseCode)
        assertEquals(true, server?.stopCalled)
    }

    @Test
    fun testActualCurlCommandFlow() {
        // Ensure sample_play.bplist exists
        val dict = NSDictionary()
        dict.put("Content-Location", NSString("http://192.168.1.188:8765/stream/cctv1.m3u8"))
        dict.put("Start-Position", NSNumber(0.0))
        val bplistBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)
        val bplistFile = java.io.File("sample_play.bplist")
        bplistFile.writeBytes(bplistBytes)

        fun runCurl(args: List<String>): String {
            val cmd = mutableListOf("curl.exe")
            cmd.addAll(args)
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            println("=== CURL CMD: ${cmd.joinToString(" ")} ===")
            println(output)
            return output
        }

        // 1. curl /server-info
        val infoOutput = runCurl(listOf("-s", "-i", "http://127.0.0.1:$testPort/server-info"))
        assertTrue(infoOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(infoOutput.contains("text/x-apple-plist+xml"))
        assertTrue(infoOutput.contains("<key>deviceid</key>"))

        // 2. curl POST /play (binary plist)
        val playOutput = runCurl(listOf(
            "-s", "-i", "-X", "POST",
            "-H", "Content-Type: application/x-apple-binary-plist",
            "--data-binary", "@${bplistFile.absolutePath}",
            "http://127.0.0.1:$testPort/play"
        ))
        assertTrue(playOutput.contains("HTTP/1.1 200 OK"))
        assertEquals("http://192.168.1.188:8765/stream/cctv1.m3u8", server?.lastPlayedUrl)

        // 3. curl /playback-info
        val playbackOutput = runCurl(listOf("-s", "-i", "http://127.0.0.1:$testPort/playback-info"))
        assertTrue(playbackOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(playbackOutput.contains("<key>duration</key>"))

        // 4. curl POST /rate (pause & resume)
        val pauseOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/rate?value=0.000000"))
        assertTrue(pauseOutput.contains("HTTP/1.1 200 OK"))
        assertEquals(false, server?.mockIsPlaying)

        val resumeOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/rate?value=1.000000"))
        assertTrue(resumeOutput.contains("HTTP/1.1 200 OK"))
        assertEquals(true, server?.mockIsPlaying)

        // 5. curl POST /scrub & GET /scrub
        val scrubSetOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/scrub?position=60.000000"))
        assertTrue(scrubSetOutput.contains("HTTP/1.1 200 OK"))
        assertEquals(60.0, server?.mockPositionSec ?: 0.0, 0.01)

        val scrubGetOutput = runCurl(listOf("-s", "-i", "http://127.0.0.1:$testPort/scrub"))
        assertTrue(scrubGetOutput.contains("HTTP/1.1 200 OK"))
        assertTrue(scrubGetOutput.contains("position: 60.000000"))

        // 6. curl POST /stop
        val stopOutput = runCurl(listOf("-s", "-i", "-X", "POST", "http://127.0.0.1:$testPort/stop"))
        assertTrue(stopOutput.contains("HTTP/1.1 200 OK"))
        assertEquals(true, server?.stopCalled)
    }

    class TestAirPlayServer(port: Int) : NanoHTTPD(port) {
        var lastPlayedUrl: String? = null
        var lastStartPosition: Double? = null
        var mockDurationSec = 3600.0
        var mockPositionSec = 0.0
        var mockIsPlaying = false
        var stopCalled = false

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            return when (uri) {
                "/server-info" -> {
                    val xml = PlistHelper.generateServerInfoXml("58:55:CA:1A:E2:88")
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", xml)
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/play" -> {
                    var bytes: ByteArray? = null
                    val lenStr = session.headers["content-length"]
                    val len = lenStr?.toIntOrNull() ?: 0
                    if (len > 0) {
                        try {
                            val buffer = ByteArray(len)
                            var totalRead = 0
                            while (totalRead < len) {
                                val r = session.inputStream.read(buffer, totalRead, len - totalRead)
                                if (r == -1) break
                                totalRead += r
                            }
                            if (totalRead > 0) {
                                bytes = buffer.copyOf(totalRead)
                            }
                        } catch (e: Exception) {}
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        val files = HashMap<String, String>()
                        try { session.parseBody(files) } catch (e: Exception) {}
                        for ((_, value) in files) {
                            if (!value.isNullOrBlank()) {
                                val tempFile = java.io.File(value)
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
                        lastPlayedUrl = req.contentLocation
                        lastStartPosition = req.startPosition
                        mockIsPlaying = true
                        val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request")
                    }
                }
                "/playback-info" -> {
                    val xml = PlistHelper.generatePlaybackInfoXml(mockDurationSec, mockPositionSec, mockIsPlaying, true)
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", xml)
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/rate" -> {
                    val valStr = session.parms["value"] ?: "1.0"
                    val v = valStr.toDoubleOrNull() ?: 1.0
                    mockIsPlaying = (v != 0.0)
                    val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                    resp.addHeader("Server", "AirTunes/130.14")
                    resp
                }
                "/scrub" -> {
                    if (method == Method.GET) {
                        val text = PlistHelper.generateScrubText(mockDurationSec, mockPositionSec)
                        val resp = newFixedLengthResponse(Response.Status.OK, "text/parameters", text)
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    } else {
                        val posStr = session.parms["position"] ?: "0.0"
                        mockPositionSec = posStr.toDoubleOrNull() ?: 0.0
                        val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                        resp.addHeader("Server", "AirTunes/130.14")
                        resp
                    }
                }
                "/stop" -> {
                    stopCalled = true
                    mockIsPlaying = false
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
}
