package com.bigeyes.tv.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Manages persistent Device ID for AirPlay (MAC-format) and UDN for DLNA/UPnP.
 * Ensures the identifiers remain consistent across app restarts so clients don't see duplicate devices.
 */
class DeviceIdManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = generateMacAddress()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    val udn: String
        get() {
            var u = prefs.getString(KEY_UDN, null)
            if (u.isNullOrBlank()) {
                u = "uuid:" + UUID.randomUUID().toString()
                prefs.edit().putString(KEY_UDN, u).apply()
            }
            return u
        }

    private fun generateMacAddress(): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()

            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(androidId.toByteArray(Charsets.UTF_8))
            // Take 6 bytes to form a standard MAC format
            val sb = StringBuilder()
            for (i in 0 until 6) {
                if (i > 0) sb.append(":")
                sb.append(String.format(Locale.US, "%02X", hash[i].toInt() and 0xFF))
            }
            sb.toString()
        } catch (e: Exception) {
            "58:55:CA:1A:E2:88"
        }
    }

    companion object {
        private const val PREFS_NAME = "bigeyes_tv_device_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "airplay_device_id"
        private const val KEY_UDN = "dlna_udn"
        const val DEFAULT_DEVICE_NAME = "BigEyes TV"

        @Volatile
        private var INSTANCE: DeviceIdManager? = null

        fun getInstance(context: Context): DeviceIdManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceIdManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
