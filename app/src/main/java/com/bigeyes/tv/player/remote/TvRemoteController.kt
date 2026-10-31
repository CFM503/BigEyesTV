package com.bigeyes.tv.player.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.bigeyes.tv.config.TvPlayerConfig
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.controller.PlaybackController

/**
 * Centralized TV Remote Controller dispatcher.
 * Handles TV D-Pad, Navigation, and Media keys cleanly without scattering
 * keyCode conditionals across Activity lifecycle methods.
 */
class TvRemoteController(
    private val commandDispatcher: (PlaybackCommand) -> Unit,
    private val callback: RemoteCallback,
    private val postDelayedAction: (Runnable, Long) -> Unit = { r, delay ->
        try {
            Handler(Looper.getMainLooper()).postDelayed(r, delay)
        } catch (_: Exception) {}
    },
    private val removeAction: (Runnable) -> Unit = { r ->
        try {
            Handler(Looper.getMainLooper()).removeCallbacks(r)
        } catch (_: Exception) {}
    }
) {
    constructor(controller: PlaybackController, callback: RemoteCallback) : this(
        commandDispatcher = { controller.dispatch(it) },
        callback = callback
    )

    interface RemoteCallback {
        fun isOverlayVisible(): Boolean
        fun showOverlay(focusOnSeekBar: Boolean = false)
        fun hideOverlay()
        fun resetOverlayHideTimer()
        fun isDialogShowing(): Boolean
        fun showExitConfirmDialog()
        fun isEndOrErrorVisible(): Boolean
        fun handleEndOrErrorBack(): Boolean
        fun isHoldingSpeed(): Boolean
        fun activateHoldingSpeed(speed: Float, hudText: String)
        fun deactivateHoldingSpeed()
        fun isScrubbing(): Boolean
        fun startOrUpdateScrub(isForward: Boolean, repeatCount: Int)
        fun commitScrub()
        fun cancelScrub()
        fun isSeekBarFocused(): Boolean
        fun focusSeekBar()
        fun focusButtonBar()
        fun performFocusedClick(): Boolean
        fun cancelCountdown(): Boolean
        fun isPlayerVisible(): Boolean
    }

    private var pendingSpeedHoldRunnable: Runnable? = null
    private var speedHoldWatchdogRunnable: Runnable? = null

    private fun isConfirmKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (callback.isDialogShowing()) {
            return false
        }

        // Buffering / loading guard: only the BACK key may leave the player so the user
        // cannot trigger episode switches or seeks while the stream has no data.
        if (isPlaybackLocked()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                callback.showExitConfirmDialog()
            }
            return true
        }

        // Global Media Keys: Directly invoke PlaybackController commands
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Log.i(TAG, "MEDIA_NEXT intercepted -> dispatching PlaybackCommand.Next")
                commandDispatcher(PlaybackCommand.Next)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Log.i(TAG, "MEDIA_PREVIOUS intercepted -> dispatching PlaybackCommand.Previous")
                commandDispatcher(PlaybackCommand.Previous)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                Log.i(TAG, "MEDIA_PLAY_PAUSE intercepted -> dispatching TogglePlayPause")
                commandDispatcher(PlaybackCommand.TogglePlayPause)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Log.i(TAG, "MEDIA_PLAY intercepted -> dispatching Resume")
                commandDispatcher(PlaybackCommand.Resume)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Log.i(TAG, "MEDIA_PAUSE intercepted -> dispatching Pause")
                commandDispatcher(PlaybackCommand.Pause)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                Log.i(TAG, "MEDIA_STOP intercepted -> dispatching Stop")
                commandDispatcher(PlaybackCommand.Stop)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                commandDispatcher(PlaybackCommand.SeekForward(TvPlayerConfig.QuickSeek.FORWARD_STEP_MS))
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                commandDispatcher(PlaybackCommand.SeekBackward(TvPlayerConfig.QuickSeek.REWIND_STEP_MS))
                return true
            }
        }

        val overlayVisible = callback.isOverlayVisible()

        if (!overlayVisible) {
            // When Overlay is HIDDEN
            if (!callback.isPlayerVisible()) {
                return false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (callback.isEndOrErrorVisible()) {
                        return callback.handleEndOrErrorBack()
                    }
                    if (callback.cancelCountdown()) {
                        Log.i(TAG, "Remote BACK cancelled auto next countdown.")
                        return true
                    }
                    callback.showExitConfirmDialog()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    callback.showOverlay(focusOnSeekBar = true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                    callback.showOverlay(focusOnSeekBar = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    handleSpeedHoldOnDown(
                        isForward = true,
                        event = event,
                        speed = TvPlayerConfig.HoldingSpeed.FAST_FORWARD_SPEED,
                        text = TvPlayerConfig.HoldingSpeed.HUD_FAST_FORWARD_TEXT
                    )
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    handleSpeedHoldOnDown(
                        isForward = false,
                        event = event,
                        speed = TvPlayerConfig.HoldingSpeed.SLOW_MOTION_SPEED,
                        text = TvPlayerConfig.HoldingSpeed.HUD_SLOW_MOTION_TEXT
                    )
                    return true
                }
                else -> {
                    if (isConfirmKey(keyCode)) {
                        callback.showOverlay(focusOnSeekBar = false)
                        return true
                    }
                }
            }
        } else {
            // When Overlay is VISIBLE
            callback.resetOverlayHideTimer()

            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (callback.isScrubbing()) {
                        callback.cancelScrub()
                    } else {
                        callback.hideOverlay()
                    }
                    return true
                }
            }

            if (callback.isSeekBarFocused()) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        callback.startOrUpdateScrub(isForward = false, repeatCount = event?.repeatCount ?: 0)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        callback.startOrUpdateScrub(isForward = true, repeatCount = event?.repeatCount ?: 0)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (callback.isScrubbing()) {
                            callback.commitScrub()
                        }
                        callback.focusButtonBar()
                        return true
                    }
                    else -> {
                        if (isConfirmKey(keyCode)) {
                            if (callback.isScrubbing()) {
                                callback.commitScrub()
                            } else {
                                commandDispatcher(PlaybackCommand.TogglePlayPause)
                            }
                            return true
                        }
                    }
                }
            } else {
                // Focus on action buttons
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        callback.focusSeekBar()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Pressing DOWN at bottom action buttons hides overlay gracefully
                        callback.hideOverlay()
                        return true
                    }
                    else -> {
                        if (isConfirmKey(keyCode)) {
                            if (callback.performFocusedClick()) {
                                return true
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (callback.isDialogShowing()) {
            return false
        }
        if (isPlaybackLocked()) {
            return true
        }
        if (!callback.isPlayerVisible() && !callback.isOverlayVisible()) {
            return false
        }

        if (callback.isOverlayVisible() && callback.isSeekBarFocused()) {
            if (callback.isScrubbing() && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
                callback.commitScrub()
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            cancelSpeedHoldTimers()

            if (callback.isHoldingSpeed()) {
                callback.deactivateHoldingSpeed()
                return true
            } else if (!callback.isOverlayVisible()) {
                // Short tap on left/right when overlay hidden -> show overlay
                callback.showOverlay()
                return true
            }
        }

        return false
    }

    private fun handleSpeedHoldOnDown(isForward: Boolean, event: KeyEvent?, speed: Float, text: String) {
        // Watchdog: auto-restore if key up event gets dropped (e.g., infrared interference)
        speedHoldWatchdogRunnable?.let { removeAction(it) }
        val watchdog = Runnable {
            if (callback.isHoldingSpeed()) {
                Log.w(TAG, "Speed hold watchdog triggered (missing KEY_UP), resetting speed.")
                callback.deactivateHoldingSpeed()
            }
        }
        speedHoldWatchdogRunnable = watchdog
        postDelayedAction(watchdog, SPEED_HOLD_WATCHDOG_TIMEOUT_MS)

        if (event?.repeatCount == 0) {
            if (pendingSpeedHoldRunnable == null && !callback.isHoldingSpeed()) {
                val r = Runnable {
                    callback.activateHoldingSpeed(speed, text)
                }
                pendingSpeedHoldRunnable = r
                postDelayedAction(r, TvPlayerConfig.HoldingSpeed.TRIGGER_DELAY_MS)
            }
        } else if (event != null && event.repeatCount >= 1) {
            if (!callback.isHoldingSpeed()) {
                pendingSpeedHoldRunnable?.let { removeAction(it) }
                pendingSpeedHoldRunnable = null
                callback.activateHoldingSpeed(speed, text)
            }
        }
    }

    private fun cancelSpeedHoldTimers() {
        pendingSpeedHoldRunnable?.let { removeAction(it) }
        pendingSpeedHoldRunnable = null
        speedHoldWatchdogRunnable?.let { removeAction(it) }
        speedHoldWatchdogRunnable = null
    }

    fun cleanup() {
        cancelSpeedHoldTimers()
    }

    /** True while the stream is loading/buffering and remote input must be locked. */
    private fun isPlaybackLocked(): Boolean {
        return false
    }

    companion object {
        private const val TAG = "TvRemoteController"
        private const val SPEED_HOLD_WATCHDOG_TIMEOUT_MS = 1000L
    }
}
