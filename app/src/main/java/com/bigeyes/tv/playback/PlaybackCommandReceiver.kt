package com.bigeyes.tv.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.controller.PlaybackController

/**
 * Receives playback control broadcasts (pause / resume / seek / stop / next / previous) sent by
 * the companion BigEyes phone app.
 *
 * These actions are delivered as broadcasts, therefore they cannot be handled by MainActivity's
 * activity intent-filters; this manifest-registered receiver is the entry point that funnels them
 * into the unified [PlaybackController] dispatch pipeline.
 *
 * PLAY / PLAY_QUEUE are intentionally ignored here: they are dispatched with startActivity so that
 * the player UI is brought to the foreground by the phone app.
 */
class PlaybackCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val incoming = intent ?: return
        val action = incoming.action
        if (action == null || !action.startsWith(ACTION_PREFIX)) return

        if (action == PlaybackIntentContract.ACTION_PLAY ||
            action == PlaybackIntentContract.ACTION_PLAY_QUEUE
        ) {
            return
        }

        val command = PlaybackIntentContract.parseCommand(incoming)
        if (command == null) {
            Log.w(TAG, "Ignoring unrecognized BigEyesTV control action: $action")
            return
        }

        Log.i(TAG, "Remote control command received: $command")
        try {
            PlaybackController.getInstance(context).dispatch(command)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to dispatch remote control command: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PlaybackCommandReceiver"
        private const val ACTION_PREFIX = "com.bigeyes.tv.action."
    }
}
