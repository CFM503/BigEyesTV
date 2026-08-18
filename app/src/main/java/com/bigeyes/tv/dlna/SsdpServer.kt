package com.bigeyes.tv.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.NetworkUtils
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * SSDP (Simple Service Discovery Protocol) server for DLNA discovery.
 * Listens for M-SEARCH on UDP 239.255.255.250:1900 and responds with MediaRenderer descriptor.
 */
class SsdpServer(
    private val context: Context,
    private val port: Int = 7000
) {
    private val deviceIdManager = DeviceIdManager.getInstance(context)
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var multicastSocket: MulticastSocket? = null
    private var isRunning = false
    private var listenThread: Thread? = null
    private var notifyExecutor: ScheduledExecutorService? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("bigeyes_ssdp_multicast")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock for SSDP: ${e.message}")
        }

        listenThread = Thread({
            runListener()
        }, "SSDP-Listener").apply { start() }

        notifyExecutor = Executors.newSingleThreadScheduledExecutor()
        notifyExecutor?.scheduleWithFixedDelay({
            sendAliveNotify()
        }, 1, 30, TimeUnit.SECONDS)

        Log.i(TAG, "SSDP server started.")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            sendByeByeNotify()
        } catch (e: Exception) {
            // ignore
        }

        notifyExecutor?.shutdownNow()
        notifyExecutor = null

        try {
            multicastSocket?.leaveGroup(InetAddress.getByName(SSDP_MULTICAST_GROUP))
        } catch (e: Exception) {
            // ignore
        }
        try {
            multicastSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        multicastSocket = null

        listenThread?.interrupt()
        listenThread = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }

        Log.i(TAG, "SSDP server stopped.")
    }

    private fun runListener() {
        try {
            val group = InetAddress.getByName(SSDP_MULTICAST_GROUP)
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                joinGroup(group)
            }
            multicastSocket = socket

            val buffer = ByteArray(4096)
            while (isRunning && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleSsdpMessage(message, packet.address, packet.port)
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP Listener error: ${e.message}", e)
        }
    }

    private fun handleSsdpMessage(message: String, remoteAddress: InetAddress, remotePort: Int) {
        if (!message.startsWith("M-SEARCH", ignoreCase = true)) return

        val headers = parseHeaders(message)
        val man = headers["man"] ?: headers["MAN"] ?: ""
        val st = headers["st"] ?: headers["ST"] ?: ""

        if (!man.contains("ssdp:discover", ignoreCase = true)) return

        val udn = deviceIdManager.udn
        val myIp = NetworkUtils.getLocalIpAddress()
        val location = "http://$myIp:$port/description.xml"

        val targets = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            udn
        )

        for (target in targets) {
            if (st.equals("ssdp:all", ignoreCase = true) || st.equals(target, ignoreCase = true)) {
                sendSearchResponse(remoteAddress, remotePort, target, location, udn)
            }
        }
    }

    private fun sendSearchResponse(
        remoteAddress: InetAddress,
        remotePort: Int,
        st: String,
        location: String,
        udn: String
    ) {
        val usn = if (st == udn) udn else "$udn::$st"
        val response = """HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
DATE: 
EXT:
LOCATION: $location
SERVER: Android/UPnP/1.0 DLNADOC/1.50 BigEyesTV/0.1.0
ST: $st
USN: $usn

""".replace("\n", "\r\n")

        try {
            val bytes = response.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, remoteAddress, remotePort)
            multicastSocket?.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send M-SEARCH response to $remoteAddress:$remotePort: ${e.message}")
        }
    }

    private fun sendAliveNotify() {
        val udn = deviceIdManager.udn
        val myIp = NetworkUtils.getLocalIpAddress()
        val location = "http://$myIp:$port/description.xml"

        val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            udn
        )

        for (nt in targets) {
            val usn = if (nt == udn) udn else "$udn::$nt"
            val notify = """NOTIFY * HTTP/1.1
HOST: $SSDP_MULTICAST_GROUP:$SSDP_PORT
CACHE-CONTROL: max-age=1800
LOCATION: $location
NT: $nt
NTS: ssdp:alive
SERVER: Android/UPnP/1.0 DLNADOC/1.50 BigEyesTV/0.1.0
USN: $usn

""".replace("\n", "\r\n")
            sendMulticast(notify)
        }
    }

    private fun sendByeByeNotify() {
        val udn = deviceIdManager.udn
        val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            udn
        )
        for (nt in targets) {
            val usn = if (nt == udn) udn else "$udn::$nt"
            val notify = """NOTIFY * HTTP/1.1
HOST: $SSDP_MULTICAST_GROUP:$SSDP_PORT
NT: $nt
NTS: ssdp:byebye
USN: $usn

""".replace("\n", "\r\n")
            sendMulticast(notify)
        }
    }

    private fun sendMulticast(message: String) {
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(SSDP_MULTICAST_GROUP)
            val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
            multicastSocket?.send(packet)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun parseHeaders(message: String): Map<String, String> {
        val map = HashMap<String, String>()
        message.lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim()
                val v = line.substring(colon + 1).trim()
                map[k] = v
            }
        }
        return map
    }

    companion object {
        private const val TAG = "SsdpServer"
        private const val SSDP_MULTICAST_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}
