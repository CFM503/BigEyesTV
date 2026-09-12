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
    private val controller: PlaybackController,
    private val callback: RemoteCallback
) {

    interface RemoteCallback {
        fun isOverlayVisible(): Boolean
        fun showOverlay(focusOnSeekBar: Boolean = false)
        fun hideOverlay()
        fun isDialogShowing(): Boolean
        fun showExitConfirmDialog()
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSpeedHoldRunnable: Runnable? = null

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (callback.isDialogShowing()) {
            return false
        }

        // Global Media Keys: Directly invoke PlaybackController commands
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Log.i(TAG, "MEDIA_NEXT intercepted -> dispatching PlaybackCommand.Next")
                controller.dispatch(PlaybackCommand.Next)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Log.i(TAG, "MEDIA_PREVIOUS intercepted -> dispatching PlaybackCommand.Previous")
                controller.dispatch(PlaybackCommand.Previous)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                Log.i(TAG, "MEDIA_PLAY_PAUSE intercepted -> dispatching TogglePlayPause")
                controller.dispatch(PlaybackCommand.TogglePlayPause)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Log.i(TAG, "MEDIA_PLAY intercepted -> dispatching Resume")
                controller.dispatch(PlaybackCommand.Resume)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Log.i(TAG, "MEDIA_PAUSE intercepted -> dispatching Pause")
                controller.dispatch(PlaybackCommand.Pause)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                Log.i(TAG, "MEDIA_STOP intercepted -> dispatching Stop")
                controller.dispatch(PlaybackCommand.Stop)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller.dispatch(PlaybackCommand.SeekForward(15000L))
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller.dispatch(PlaybackCommand.SeekBackward(15000L))
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
                    if (callback.cancelCountdown()) {
                        Log.i(TAG, "Remote BACK cancelled auto next countdown.")
                        return true
                    }
                    callback.showExitConfirmDialog()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    callback.showOverlay(focusOnSeekBar = false)
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
            }
        } else {
            // When Overlay is VISIBLE
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
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (callback.isScrubbing()) {
                            callback.commitScrub()
                        } else {
                            controller.dispatch(PlaybackCommand.TogglePlayPause)
                        }
                        return true
                    }
                }
            } else {
                // Focus on action buttons
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        callback.focusSeekBar()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (callback.performFocusedClick()) {
                            return true
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
            pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingSpeedHoldRunnable = null

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
        if (event?.repeatCount == 0) {
            if (pendingSpeedHoldRunnable == null && !callback.isHoldingSpeed()) {
                val r = Runnable {
                    callback.activateHoldingSpeed(speed, text)
                }
                pendingSpeedHoldRunnable = r
                mainHandler.postDelayed(r, TvPlayerConfig.HoldingSpeed.TRIGGER_DELAY_MS)
            }
        } else if (event != null && event.repeatCount >= 1) {
            if (!callback.isHoldingSpeed()) {
                pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingSpeedHoldRunnable = null
                callback.activateHoldingSpeed(speed, text)
            }
        }
    }

    fun cleanup() {
        pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSpeedHoldRunnable = null
    }

    companion object {
        private const val TAG = "TvRemoteController"
    }
}
