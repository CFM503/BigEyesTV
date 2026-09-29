package com.bigeyes.tv.dlna

import com.bigeyes.tv.BuildConfig
import com.bigeyes.tv.MockSession
import com.bigeyes.tv.fakes.FakeDeviceIdentity
import com.bigeyes.tv.fakes.FakePlayback
import com.bigeyes.tv.fakes.FakeVolumeController
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the *real* [DlnaActionHandler] instead of a local copy of its logic, so regressions in
 * routing, SOAP serialization and device description actually fail the build.
 */
class DlnaActionHandlerTest {

    private lateinit var player: FakePlayback
    private lateinit var identity: FakeDeviceIdentity
    private lateinit var volume: FakeVolumeController
    private lateinit var handler: DlnaActionHandler

    @Before
    fun setUp() {
        player = FakePlayback()
        identity = FakeDeviceIdentity()
        volume = FakeVolumeController(percent = 40, mutedState = false)
        handler = DlnaActionHandler(player, identity, volume, port = 7000)
    }

    private fun body(response: Response): String =
        response.data.readBytes().toString(Charsets.UTF_8)

    private fun soapSession(uri: String, soapAction: String, soapBody: String): MockSession {
        val bytes = soapBody.toByteArray(Charsets.UTF_8)
        return MockSession(
            uri = uri,
            method = NanoHTTPD.Method.POST,
            headers = mapOf(
                "content-length" to bytes.size.toString(),
                "soapaction" to "\"$soapAction\""
            ),
            body = bytes
        )
    }

    private fun soapBody(action: String, service: String, args: String = "") =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body>
        <u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">
            <InstanceID>0</InstanceID>
            $args
        </u:$action>
    </s:Body>
</s:Envelope>"""

    private fun get(uri: String, headers: Map<String, String> = emptyMap()) =
        MockSession(uri, NanoHTTPD.Method.GET, headers)

    // ---------------------------------------------------------------- routing

    @Test
    fun testCanHandleCoversEveryAdvertisedEndpoint() {
        assertTrue(handler.canHandle("/description.xml"))
        assertTrue(handler.canHandle("/avtransport.xml"))
        assertTrue(handler.canHandle("/renderingcontrol.xml"))
        assertTrue(handler.canHandle("/connectionmanager.xml"))
        assertTrue(handler.canHandle("/upnp/control/avtransport"))
        assertTrue(handler.canHandle("/upnp/control/renderingcontrol"))
        assertTrue(handler.canHandle("/upnp/control/connectionmanager"))
        assertTrue(handler.canHandle("/upnp/event/avtransport"))
        assertFalse(handler.canHandle("/server-info"))
        assertFalse(handler.canHandle("/index.html"))
    }

    @Test
    fun testUnknownRouteIs404() {
        val resp = handler.handleRequest(get("/nope"))
        assertEquals(404, resp.status.requestStatus)
    }

    // ------------------------------------------------------- device description

    @Test
    fun testDescriptionUsesIdentityAndAppVersion() {
        val resp = handler.handleRequest(get("/description.xml"))
        val xml = body(resp)

        assertEquals(200, resp.status.requestStatus)
        assertTrue(xml.contains("<friendlyName>${identity.deviceName}</friendlyName>"))
        assertTrue(xml.contains("<UDN>${identity.udn}</UDN>"))
        assertTrue(xml.contains("<serialNumber>${identity.deviceId}</serialNumber>"))
        assertTrue(xml.contains("<modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>"))
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(xml.contains("<eventSubURL>/upnp/event/avtransport</eventSubURL>"))
    }

    @Test
    fun testScpdFilesDeclareTheirActions() {
        assertTrue(body(handler.handleRequest(get("/avtransport.xml")))
            .contains("<action><name>SetNextAVTransportURI</name></action>"))
        assertTrue(body(handler.handleRequest(get("/avtransport.xml")))
            .contains("<action><name>GetPositionInfo</name></action>"))

        val rcpd = body(handler.handleRequest(get("/renderingcontrol.xml")))
        assertTrue(rcpd.contains("<action><name>GetVolume</name></action>"))
        assertTrue(rcpd.contains("<action><name>SetMute</name></action>"))

        val cmd = body(handler.handleRequest(get("/connectionmanager.xml")))
        assertTrue(cmd.contains("<action><name>GetProtocolInfo</name></action>"))
    }

    // ------------------------------------------------------------ GENA endpoint

    @Test
    fun testEventSubscriptionEndpointAnswersGetWith405() {
        val resp = handler.handleRequest(get("/upnp/event/avtransport"))
        assertEquals(405, resp.status.requestStatus)
        assertTrue(body(resp).contains("SUBSCRIBE"))
    }

    @Test
    fun testEventSubscriptionRejectsMethodsNanoHttpdAcceptsButGenaDoesNot() {
        // NanoHTTPD's Method enum has no SUBSCRIBE/UNSUBSCRIBE, so those requests are rejected by
        // the parser before reaching the handler; HEAD is the closest reachable proxy.
        val resp = handler.handleRequest(
            MockSession("/upnp/event/avtransport", NanoHTTPD.Method.HEAD)
        )
        assertEquals(400, resp.status.requestStatus)
    }

    // ------------------------------------------------------ RenderingControl

    @Test
    fun testGetVolumeReturnsFakeVolume() {
        volume.percent = 55
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/renderingcontrol",
                "urn:schemas-upnp-org:service:RenderingControl:1#GetVolume",
                soapBody("GetVolume", "RenderingControl", "<Channel>Master</Channel>")
            )
        )
        assertEquals(200, resp.status.requestStatus)
        assertTrue(body(resp).contains("<CurrentVolume>55</CurrentVolume>"))
    }

    @Test
    fun testSetVolumeWritesThroughToVolumeController() {
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/renderingcontrol",
                "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume",
                soapBody(
                    "SetVolume",
                    "RenderingControl",
                    "<Channel>Master</Channel><DesiredVolume>73</DesiredVolume>"
                )
            )
        )
        assertEquals(200, resp.status.requestStatus)
        assertEquals(73, volume.percent)
    }

    @Test
    fun testSetMuteAndGetMuteRoundTrip() {
        handler.handleRequest(
            soapSession(
                "/upnp/control/renderingcontrol",
                "urn:schemas-upnp-org:service:RenderingControl:1#SetMute",
                soapBody(
                    "SetMute",
                    "RenderingControl",
                    "<Channel>Master</Channel><DesiredMute>1</DesiredMute>"
                )
            )
        )
        assertTrue(volume.mutedState)

        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/renderingcontrol",
                "urn:schemas-upnp-org:service:RenderingControl:1#GetMute",
                soapBody("GetMute", "RenderingControl", "<Channel>Master</Channel>")
            )
        )
        assertTrue(body(resp).contains("<CurrentMute>1</CurrentMute>"))
    }

    @Test
    fun testUnknownRenderingControlActionReturnsUpnpFault401() {
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/renderingcontrol",
                "urn:schemas-upnp-org:service:RenderingControl:1#Browse",
                soapBody("Browse", "RenderingControl")
            )
        )
        val xml = body(resp)
        assertEquals(500, resp.status.requestStatus)
        assertTrue(xml.contains("<errorCode>401</errorCode>"))
        assertTrue(xml.contains("<errorDescription>Invalid Action</errorDescription>"))
        assertTrue(xml.contains("<faultstring>UPnPError</faultstring>"))
        assertEquals(40, volume.percent)
    }

    @Test
    fun testUnknownConnectionManagerActionReturnsUpnpFault401() {
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/connectionmanager",
                "urn:schemas-upnp-org:service:ConnectionManager:1#PrepareForConnection",
                soapBody("PrepareForConnection", "ConnectionManager")
            )
        )
        assertEquals(500, resp.status.requestStatus)
        assertTrue(body(resp).contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun testGetProtocolInfoAdvertisesSupportedMimeTypes() {
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/connectionmanager",
                "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
                soapBody("GetProtocolInfo", "ConnectionManager")
            )
        )
        val xml = body(resp)
        assertEquals(200, resp.status.requestStatus)
        assertTrue(xml.contains("application/vnd.apple.mpegurl"))
        assertTrue(xml.contains("<Sink></Sink>"))
    }

    // ------------------------------------------------------------- AVTransport

    @Test
    fun testSetAvTransportUriStartsPlayback() {
        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                soapBody(
                    "SetAVTransportURI",
                    "AVTransport",
                    "<CurrentURI>http://192.168.1.50:8765/stream/ep1.m3u8</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>"
                )
            )
        )
        assertEquals(200, resp.status.requestStatus)
        assertEquals(
            "play:http://192.168.1.50:8765/stream/ep1.m3u8:0:",
            player.calls.single()
        )
    }

    @Test
    fun testSetNextAvTransportUriFeedsQueue() {
        handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetNextAVTransportURI",
                soapBody(
                    "SetNextAVTransportURI",
                    "AVTransport",
                    "<NextURI>http://192.168.1.50:8765/stream/ep2.m3u8</NextURI>"
                )
            )
        )
        assertEquals(listOf("next:http://192.168.1.50:8765/stream/ep2.m3u8"), player.calls)
    }

    @Test
    fun testTransportControlsDispatchToPlayer() {
        handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#Pause",
                soapBody("Pause", "AVTransport")
            )
        )
        handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#Next",
                soapBody("Next", "AVTransport")
            )
        )
        handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#Seek",
                soapBody("Seek", "AVTransport", "<Unit>REL_TIME</Unit><Target>00:01:30</Target>")
            )
        )
        assertEquals(listOf("pause", "playNext", "seek:90000"), player.calls)
    }

    @Test
    fun testGetPositionInfoReportsRealTrackAndTime() {
        player.trackTitle = "庆余年 第二季 第05集"
        player.trackIndex = 5
        player.durationMillis = 60_000L
        player.positionMs = 0L

        val resp = handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
                soapBody("GetPositionInfo", "AVTransport")
            )
        )
        val xml = body(resp)

        assertEquals(200, resp.status.requestStatus)
        assertTrue("<Track>5</Track>" in xml)
        assertTrue("<TrackDuration>00:01:00</TrackDuration>" in xml)
        assertTrue("NOT_IMPLEMENTED" in xml)
    }

    @Test
    fun testGetPositionInfoEmitsDidlLiteWhenUrlPresent() {
        player.trackTitle = "测试剧集"
        player.trackIndex = 1
        handler.handleRequest(
            soapSession(
                "/upnp/control/avtransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                soapBody(
                    "SetAVTransportURI",
                    "AVTransport",
                    "<CurrentURI>http://192.168.1.50/a&amp;b.m3u8</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>"
                )
            )
        )

        val xml = body(
            handler.handleRequest(
                soapSession(
                    "/upnp/control/avtransport",
                    "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
                    soapBody("GetPositionInfo", "AVTransport")
                )
            )
        )
        assertTrue("DIDL-Lite" in xml)
        assertTrue("测试剧集" in xml)
        assertTrue("&amp;amp;" in xml)
    }
}
