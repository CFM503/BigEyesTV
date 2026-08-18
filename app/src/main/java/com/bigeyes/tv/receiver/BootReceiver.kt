package com.bigeyes.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bigeyes.tv.service.TvReceiverService

/**
 * BroadcastReceiver for handling device boot completion.
 * Automatically starts TvReceiverService so the TV receiver is ready
 * for AirPlay and DLNA casting immediately after TV startup without
 * requiring the user to manually launch the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "BootReceiver received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                val serviceIntent = Intent(context, TvReceiverService::class.java).apply {
                    this.action = TvReceiverService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "TvReceiverService started successfully on boot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TvReceiverService on boot", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
