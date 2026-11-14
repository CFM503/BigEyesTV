package com.bigeyes.tv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.bigeyes.tv.utils.DeviceIdManager

/**
 * AirPlay mDNS Service Discovery.
 * Advertises _airplay._tcp. service using Android NsdManager.
 */
class AirPlayDiscoveryService(
    private val context: Context,
    private val port: Int = 7000
) {
    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false
    private var isStarting = false

    @Synchronized
    fun start() {
        if (isRegistered || isStarting) return
        isStarting = true

        // 1. Acquire MulticastLock to ensure mDNS packets are transmitted even under power save
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("bigeyes_airplay_multicast")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
                Log.d(TAG, "MulticastLock acquired for AirPlay mDNS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }

        // 2. Prepare NsdServiceInfo for _airplay._tcp.
        val deviceIdManager = DeviceIdManager.getInstance(context)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceIdManager.deviceName
            serviceType = "_airplay._tcp."
            this.port = this@AirPlayDiscoveryService.port

            // TXT records
            setAttribute("deviceid", deviceIdManager.deviceId)
            setAttribute("features", "0x7") // Video + Photo + VideoFairPlay flag for AppleTV2,1 compatibility
            setAttribute("model", "AppleTV2,1")
            setAttribute("srcvers", "130.14")
            setAttribute("protovers", "1.0")
            setAttribute("flags", "0x4")
            setAttribute("vv", "2")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isStarting = false
                isRegistered = true
                Log.i(
                    TAG,
                    "AirPlay service registered successfully: name=${registeredService.serviceName}, port=${registeredService.port}, deviceid=${deviceIdManager.deviceId}"
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isStarting = false
                isRegistered = false
                releaseMulticastLock()
                Log.e(TAG, "AirPlay service registration failed with errorCode: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                Log.i(TAG, "AirPlay service unregistered.")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "AirPlay service unregistration failed with errorCode: $errorCode")
            }
        }
        registrationListener = listener

        try {
            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            isStarting = false
            registrationListener = null
            releaseMulticastLock()
            Log.e(TAG, "Exception during registerService", e)
        }
    }

    @Synchronized
    fun stop() {
        isStarting = false
        val listener = registrationListener
        registrationListener = null

        if (listener != null) {
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during unregisterService", e)
            }
        }
        isRegistered = false
        releaseMulticastLock()
    }

    fun isServiceActive(): Boolean = isRegistered || isStarting

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AirPlayDiscovery"
    }
}
