package com.bigeyes.tv.player

import android.view.KeyEvent
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.remote.TvRemoteController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TvRemoteControllerTest {

    private val dispatchedCommands = mutableListOf<PlaybackCommand>()
    private lateinit var fakeCallback: FakeRemoteCallback
    private lateinit var remoteController: TvRemoteController

    class FakeRemoteCallback : TvRemoteController.RemoteCallback {
        var overlayVisible = false
        var dialogShowing = false
        var endOrErrorVisible = false
        var playerVisible = true
        var holdingSpeed = false
        var scrubbing = false
        var seekBarFocused = false
        var countdownActive = false

        var showOverlayCalls = 0
        var hideOverlayCalls = 0
        var resetOverlayTimerCalls = 0
        var showExitDialogCalls = 0
        var endOrErrorBackCalls = 0
        var activateSpeedCalls = mutableListOf<Float>()
        var deactivateSpeedCalls = 0
        var focusSeekBarCalls = 0
        var focusButtonBarCalls = 0
        var performFocusedClickCalls = 0
        var cancelCountdownCalls = 0

        override fun isOverlayVisible(): Boolean = overlayVisible
        override fun showOverlay(focusOnSeekBar: Boolean) { showOverlayCalls++ ; overlayVisible = true }
        override fun hideOverlay() { hideOverlayCalls++ ; overlayVisible = false }
        override fun resetOverlayHideTimer() { resetOverlayTimerCalls++ }
        override fun isDialogShowing(): Boolean = dialogShowing
        override fun showExitConfirmDialog() { showExitDialogCalls++ }
        override fun isEndOrErrorVisible(): Boolean = endOrErrorVisible
        override fun handleEndOrErrorBack(): Boolean { endOrErrorBackCalls++; return true }
        override fun isHoldingSpeed(): Boolean = holdingSpeed
        override fun activateHoldingSpeed(speed: Float, hudText: String) { holdingSpeed = true; activateSpeedCalls.add(speed) }
        override fun deactivateHoldingSpeed() { holdingSpeed = false; deactivateSpeedCalls++ }
        override fun isScrubbing(): Boolean = scrubbing
        override fun startOrUpdateScrub(isForward: Boolean, repeatCount: Int) { scrubbing = true }
        override fun commitScrub() { scrubbing = false }
        override fun cancelScrub() { scrubbing = false }
        override fun isSeekBarFocused(): Boolean = seekBarFocused
        override fun focusSeekBar() { focusSeekBarCalls++ ; seekBarFocused = true }
        override fun focusButtonBar() { focusButtonBarCalls++ ; seekBarFocused = false }
        override fun performFocusedClick(): Boolean { performFocusedClickCalls++; return true }
        override fun cancelCountdown(): Boolean {
            if (countdownActive) {
                cancelCountdownCalls++
                countdownActive = false
                return true
            }
            return false
        }
        override fun isPlayerVisible(): Boolean = playerVisible
    }

    @Before
    fun setUp() {
        dispatchedCommands.clear()
        fakeCallback = FakeRemoteCallback()
        remoteController = TvRemoteController(
            commandDispatcher = { dispatchedCommands.add(it) },
            callback = fakeCallback,
            postDelayedAction = { _, _ -> },
            removeAction = { _ -> }
        )
    }

    @Test
    fun testGlobalMediaKeys() {
        val nextConsumed = remoteController.onKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, null)
        assertTrue(nextConsumed)
        assertTrue(dispatchedCommands.lastOrNull() is PlaybackCommand.Next)

        val prevConsumed = remoteController.onKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, null)
        assertTrue(prevConsumed)
        assertTrue(dispatchedCommands.lastOrNull() is PlaybackCommand.Previous)

        val pauseConsumed = remoteController.onKeyDown(KeyEvent.KEYCODE_MEDIA_PAUSE, null)
        assertTrue(pauseConsumed)
        assertTrue(dispatchedCommands.lastOrNull() is PlaybackCommand.Pause)

        val playConsumed = remoteController.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY, null)
        assertTrue(playConsumed)
        assertTrue(dispatchedCommands.lastOrNull() is PlaybackCommand.Resume)
    }

    @Test
    fun testDialogShowingDoesNotIntercept() {
        fakeCallback.dialogShowing = true
        val consumed = remoteController.onKeyDown(KeyEvent.KEYCODE_BACK, null)
        assertFalse("When dialog is showing, controller must pass key down to system/dialog", consumed)

        val upConsumed = remoteController.onKeyUp(KeyEvent.KEYCODE_BACK, null)
        assertFalse("When dialog is showing, controller must pass key up to system/dialog", upConsumed)
    }

    @Test
    fun testBackKeyInEndOrErrorState() {
        fakeCallback.overlayVisible = false
        fakeCallback.endOrErrorVisible = true

        val consumed = remoteController.onKeyDown(KeyEvent.KEYCODE_BACK, null)
        assertTrue(consumed)
        assertEquals(1, fakeCallback.endOrErrorBackCalls)
        assertEquals(0, fakeCallback.showExitDialogCalls)
    }

    @Test
    fun testBackKeyInCountdownState() {
        fakeCallback.overlayVisible = false
        fakeCallback.countdownActive = true

        val consumed = remoteController.onKeyDown(KeyEvent.KEYCODE_BACK, null)
        assertTrue(consumed)
        assertEquals(1, fakeCallback.cancelCountdownCalls)
        assertEquals(0, fakeCallback.showExitDialogCalls)
    }

    @Test
    fun testConfirmKeysCompatibility() {
        fakeCallback.overlayVisible = false

        // NUMPAD_ENTER should show overlay
        val consumedNumpad = remoteController.onKeyDown(KeyEvent.KEYCODE_NUMPAD_ENTER, null)
        assertTrue(consumedNumpad)
        assertEquals(1, fakeCallback.showOverlayCalls)

        // BUTTON_A should also show overlay
        fakeCallback.overlayVisible = false
        val consumedButtonA = remoteController.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, null)
        assertTrue(consumedButtonA)
        assertEquals(2, fakeCallback.showOverlayCalls)
    }

    @Test
    fun testOverlayNavigationResetsTimerAndDownHidesOverlay() {
        fakeCallback.overlayVisible = true
        fakeCallback.seekBarFocused = false

        // In button bar, pressing DPAD_DOWN should hide overlay
        val consumedDown = remoteController.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, null)
        assertTrue(consumedDown)
        assertEquals(1, fakeCallback.hideOverlayCalls)
        assertTrue(fakeCallback.resetOverlayTimerCalls >= 1)
    }
}
