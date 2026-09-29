package com.bigeyes.tv.utils

/**
 * Identity values advertised by the DLNA device description and the SSDP/GENA endpoints.
 * [DeviceIdManager] implements it; tests substitute a fixed value.
 */
interface DeviceIdentity {
    val deviceName: String
    val deviceId: String
    val udn: String
}
