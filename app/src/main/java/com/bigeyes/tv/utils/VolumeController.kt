package com.bigeyes.tv.utils

import android.media.AudioManager
import android.content.Context

/**
 * UPnP RenderingControl volume/mute bridge to the Android audio stream.
 * Values are exposed on the UPnP 0-100 scale and mapped onto the device stream range.
 */
interface VolumeController {
    fun getVolume(): Int
    fun setVolume(percent: Int)
    fun isMuted(): Boolean
    fun setMuted(muted: Boolean)
}

class AudioManagerVolumeController(context: Context) : VolumeController {
    private val appContext = context.applicationContext

    private val audioManager: AudioManager?
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun getVolume(): Int {
        val am = audioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
    }

    override fun setVolume(percent: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val clamped = percent.coerceIn(0, 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped * max / 100, 0)
    }

    override fun isMuted(): Boolean {
        val am = audioManager ?: return false
        return am.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    override fun setMuted(muted: Boolean) {
        val am = audioManager ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }
}
