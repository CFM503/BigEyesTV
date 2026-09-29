package com.bigeyes.tv.utils

import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.ByteArrayInputStream
import java.util.Locale

data class AirPlayPlayRequest(
    val contentLocation: String,
    val startPosition: Double = 0.0
)

data class AirPlayPropertyRequest(
    /** AirPlay volume in the 0.0..1.0 range, or null when the payload carries no volume. */
    val volume: Double? = null
)

object PlistHelper {

    /**
     * Parse /play request body from iOS client.
     * Supports:
     * 1. Binary Plist (bplist00)
     * 2. XML Plist (<?xml... <plist...)
     * 3. Plain text parameters (Content-Location: ...\nStart-Position: ...)
     */
    fun parsePlayRequest(body: ByteArray, contentType: String?): AirPlayPlayRequest? {
        if (body.isEmpty()) return null

        // 1. Try parsing as binary plist or XML plist using dd-plist
        val isBinaryPlist = body.size >= 8 &&
                body[0] == 'b'.code.toByte() &&
                body[1] == 'p'.code.toByte() &&
                body[2] == 'l'.code.toByte() &&
                body[3] == 'i'.code.toByte() &&
                body[4] == 's'.code.toByte() &&
                body[5] == 't'.code.toByte()

        val isXmlPlist = contentType?.contains("plist", ignoreCase = true) == true ||
                String(body.take(64).toByteArray(), Charsets.UTF_8).contains("<plist", ignoreCase = true)

        if (isBinaryPlist || isXmlPlist) {
            try {
                val rootObject = PropertyListParser.parse(body)
                if (rootObject is NSDictionary) {
                    val locationObj = rootObject.objectForKey("Content-Location")
                    val startPosObj = rootObject.objectForKey("Start-Position")

                    val location = when (locationObj) {
                        is NSString -> locationObj.content
                        else -> locationObj?.toString()
                    }

                    val startPos = when (startPosObj) {
                        is NSNumber -> startPosObj.doubleValue()
                        else -> startPosObj?.toString()?.toDoubleOrNull() ?: 0.0
                    }

                    if (!location.isNullOrBlank()) {
                        return AirPlayPlayRequest(contentLocation = location.trim(), startPosition = startPos)
                    }
                }
            } catch (e: Exception) {
                // Fallback to text parser below
            }
        }

        // 2. Try parsing as plain text parameters:
        // Content-Location: http://...
        // Start-Position: 0.150000
        try {
            val text = String(body, Charsets.UTF_8)
            var location: String? = null
            var startPos = 0.0

            text.lines().forEach { line ->
                val trimmed = line.trim()
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val key = trimmed.substring(0, colonIdx).trim()
                    val value = trimmed.substring(colonIdx + 1).trim()
                    if (key.equals("Content-Location", ignoreCase = true)) {
                        location = value
                    } else if (key.equals("Start-Position", ignoreCase = true)) {
                        startPos = value.toDoubleOrNull() ?: 0.0
                    }
                }
            }

            if (!location.isNullOrBlank()) {
                return AirPlayPlayRequest(contentLocation = location!!.trim(), startPosition = startPos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * Generate XML Plist response for GET /server-info
     */
    fun generateServerInfoXml(
        deviceId: String,
        model: String = "AppleTV2,1",
        features: Long = 7L,
        srcvers: String = "130.14",
        protovers: String = "1.0"
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>deviceid</key>
	<string>$deviceId</string>
	<key>features</key>
	<integer>$features</integer>
	<key>model</key>
	<string>$model</string>
	<key>protovers</key>
	<string>$protovers</string>
	<key>srcvers</key>
	<string>$srcvers</string>
</dict>
</plist>"""
    }

    /**
     * Generate XML Plist response for GET /playback-info
     */
    fun generatePlaybackInfoXml(
        durationSec: Double,
        positionSec: Double,
        isPlaying: Boolean,
        isReady: Boolean = true
    ): String {
        val rate = if (isPlaying) 1.0 else 0.0
        val durStr = String.format(Locale.US, "%.6f", if (durationSec > 0) durationSec else 0.0)
        val posStr = String.format(Locale.US, "%.6f", if (positionSec > 0) positionSec else 0.0)
        val rateStr = String.format(Locale.US, "%.6f", rate)
        val readyStr = if (isReady) "<true/>" else "<false/>"

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>duration</key>
	<real>$durStr</real>
	<key>position</key>
	<real>$posStr</real>
	<key>rate</key>
	<real>$rateStr</real>
	<key>readyToPlay</key>
	$readyStr
	<key>playbackBufferEmpty</key>
	<false/>
	<key>playbackBufferFull</key>
	<true/>
	<key>playbackLikelyToKeepUp</key>
	<true/>
	<key>loadedTimeRanges</key>
	<array>
		<dict>
			<key>duration</key>
			<real>$durStr</real>
			<key>start</key>
			<real>0.000000</real>
		</dict>
	</array>
	<key>seekableTimeRanges</key>
	<array>
		<dict>
			<key>duration</key>
			<real>$durStr</real>
			<key>start</key>
			<real>0.000000</real>
		</dict>
	</array>
</dict>
</plist>"""
    }

    /**
     * Generate plain text response for GET /scrub
     */
    fun generateScrubText(durationSec: Double, positionSec: Double): String {
        val durStr = String.format(Locale.US, "%.6f", if (durationSec > 0) durationSec else 0.0)
        val posStr = String.format(Locale.US, "%.6f", if (positionSec > 0) positionSec else 0.0)
        return "duration: $durStr\nposition: $posStr\n"
    }

    /**
     * Parse a POST /setProperty body (binary/XML plist or plain text) for supported keys.
     */
    fun parsePropertyRequest(body: ByteArray, contentType: String?): AirPlayPropertyRequest? {
        if (body.isEmpty()) return null

        val isBinaryPlist = body.size >= 8 &&
                body[0] == 'b'.code.toByte() &&
                body[1] == 'p'.code.toByte() &&
                body[2] == 'l'.code.toByte() &&
                body[3] == 'i'.code.toByte() &&
                body[4] == 's'.code.toByte() &&
                body[5] == 't'.code.toByte()
        val isXmlPlist = contentType?.contains("plist", ignoreCase = true) == true ||
                String(body.take(64).toByteArray(), Charsets.UTF_8).contains("<plist", ignoreCase = true)

        if (isBinaryPlist || isXmlPlist) {
            try {
                val root = PropertyListParser.parse(body)
                if (root is NSDictionary) {
                    val volumeObj = root.objectForKey("volume")
                    val volume = when (volumeObj) {
                        is NSNumber -> volumeObj.doubleValue()
                        else -> volumeObj?.toString()?.toDoubleOrNull()
                    }
                    if (volume != null) return AirPlayPropertyRequest(volume)
                }
            } catch (e: Exception) {
                // fall through to the plain text parser
            }
        }

        try {
            val text = String(body, Charsets.UTF_8)
            text.lines().forEach { line ->
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim()
                    val value = line.substring(colonIdx + 1).trim()
                    if (key.equals("volume", ignoreCase = true)) {
                        value.toDoubleOrNull()?.let { return AirPlayPropertyRequest(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore malformed payloads
        }

        return AirPlayPropertyRequest()
    }

    /**
     * Generate XML Plist response for GET /getProperty
     */
    fun generatePlaybackPropertiesXml(
        durationSec: Double,
        positionSec: Double,
        isPlaying: Boolean,
        volume: Double
    ): String {
        val rate = if (isPlaying) 1.0 else 0.0
        val durStr = String.format(Locale.US, "%.6f", if (durationSec > 0) durationSec else 0.0)
        val posStr = String.format(Locale.US, "%.6f", if (positionSec > 0) positionSec else 0.0)
        val rateStr = String.format(Locale.US, "%.6f", rate)
        val volStr = String.format(Locale.US, "%.6f", volume.coerceIn(0.0, 1.0))
        val playingTag = if (isPlaying) "<true/>" else "<false/>"

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>duration</key>
	<real>$durStr</real>
	<key>position</key>
	<real>$posStr</real>
	<key>rate</key>
	<real>$rateStr</real>
	<key>playbackLikelyToKeepUp</key>
	$playingTag
	<key>volume</key>
	<real>$volStr</real>
	<key>readyToPlay</key>
	<true/>
</dict>
</plist>"""
    }

    /**
     * Generate XML Plist response for GET /slideshow-features.
     * The TV receiver only handles video, so an empty transition set is advertised.
     */
    fun generateSlideshowFeaturesXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>supportsPhotoCaching</key>
	<false/>
	<key>transitions</key>
	<array/>
	<key>themes</key>
	<array/>
</dict>
</plist>"""
    }
}
