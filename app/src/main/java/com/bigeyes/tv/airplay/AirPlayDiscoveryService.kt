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

    fun start() {
        if (isRegistered) return

        // 1. Acquire MulticastLock to ensure mDNS packets are transmitted even under power save
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("bigeyes_airplay_multicast")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
            Log.d(TAG, "MulticastLock acquired for AirPlay mDNS")
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

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isRegistered = true
                Log.i(
                    TAG,
                    "AirPlay service registered successfully: name=${registeredService.serviceName}, port=${registeredService.port}, deviceid=${deviceIdManager.deviceId}"
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
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

        try {
            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerService", e)
        }
    }

    fun stop() {
        if (!isRegistered) return

        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during unregisterService", e)
        } finally {
            isRegistered = false
            registrationListener = null
        }

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
