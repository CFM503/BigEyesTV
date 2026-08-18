package com.bigeyes.tv

import android.content.Context
import com.bigeyes.tv.airplay.AirPlayHttpHandler
import com.bigeyes.tv.dlna.DlnaActionHandler
import com.bigeyes.tv.player.TvPlayerManager
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.HashMap

class MockSession(
    private val uri: String,
    private val method: Method,
    private val headers: Map<String, String> = emptyMap(),
    private val parms: Map<String, String> = emptyMap(),
    private val body: ByteArray = ByteArray(0)
) : IHTTPSession {
    override fun execute() {}
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = headers
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun getMethod(): Method = method
    override fun getParms(): Map<String, String> = parms
    override fun getParameters(): Map<String, List<String>> = parms.mapValues { listOf(it.value) }
    override fun getQueryParameterString(): String = ""
    override fun getUri(): String = uri
    override fun getRemoteIpAddress(): String = "192.168.1.100"
    override fun getRemoteHostName(): String = "iPhone"

    override fun parseBody(files: MutableMap<String, String>?) {
        if (body.isNotEmpty() && files != null) {
            val text = String(body, Charsets.UTF_8)
            files["postData"] = text
        }
    }
}
