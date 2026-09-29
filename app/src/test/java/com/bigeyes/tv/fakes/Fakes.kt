package com.bigeyes.tv.fakes

import com.bigeyes.tv.player.PlaybackFacade
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.utils.DeviceIdentity
import com.bigeyes.tv.utils.VolumeController

/** Deterministic [DeviceIdentity] so the DLNA description can be asserted without a Context. */
class FakeDeviceIdentity(
    override val deviceName: String = "BigEyesTV (test)",
    override val deviceId: String = "AA:BB:CC:DD:EE:FF",
    override val udn: String = "uuid:aa-bb-cc-dd-ee-ff"
) : DeviceIdentity

/** In-memory [VolumeController] so RenderingControl volume/mute actions are observable. */
class FakeVolumeController(
    var percent: Int = 40,
    var mutedState: Boolean = false
) : VolumeController {
    override fun getVolume(): Int = percent
    override fun setVolume(percent: Int) {
        this.percent = percent.coerceIn(0, 100)
    }

    override fun isMuted(): Boolean = mutedState
    override fun setMuted(muted: Boolean) {
        this.mutedState = muted
    }
}

/** Recording [PlaybackFacade] so DLNA/AirPlay protocol handlers can be exercised for real. */
class FakePlayback : PlaybackFacade {

    val calls = mutableListOf<String>()

    var durationMillis: Long = 60_000L
    var positionMs: Long = 0L
    var playing: Boolean = false
    var ready: Boolean = true
    var ended: Boolean = false
    var volumeLevel: Double = 1.0
    var trackTitle: String = "Episode 1"
    var trackIndex: Int = 1

    override var currentUrl: String? = null
        private set

    override var currentState: PlayerState = PlayerState.IDLE
        private set

    override fun play(url: String, startPositionMs: Long, title: String?) {
        calls += "play:$url:$startPositionMs:${title ?: ""}"
        currentUrl = url
        playing = true
        ready = true
        ended = false
        currentState = PlayerState.READY
    }

    override fun pause() {
        calls += "pause"
        playing = false
    }

    override fun resume() {
        calls += "resume"
        playing = true
        ended = false
        currentState = PlayerState.READY
    }

    override fun stop() {
        calls += "stop"
        playing = false
        currentUrl = null
        currentState = PlayerState.IDLE
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
        this.positionMs = positionMs
    }

    override fun setNextUrl(url: String?) {
        calls += "next:$url"
    }

    override fun playNext(): Boolean {
        calls += "playNext"
        return true
    }

    override fun playPrevious(): Boolean {
        calls += "playPrevious"
        return true
    }

    override fun getDurationMs(): Long = durationMillis
    override fun getCurrentPositionMs(): Long = positionMs
    override fun isPlaying(): Boolean = playing
    override fun isEnded(): Boolean = ended
    override fun isReady(): Boolean = ready

    override fun getPlaybackVolume(): Double = volumeLevel

    override fun setPlaybackVolume(volume: Double) {
        calls += "setVolume:$volume"
        this.volumeLevel = volume
    }

    override fun currentTrackTitle(): String = trackTitle
    override fun currentTrackIndex(): Int = trackIndex
}
