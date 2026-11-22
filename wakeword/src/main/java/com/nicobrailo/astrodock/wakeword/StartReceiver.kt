package com.nicobrailo.astrodock.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Starts the listener when the device boots and after the app is updated,
// which stops it, if it is enabled. Nothing else would until the settings
// screen was next opened.
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (WakewordSettings.enabled(context)) WakewordSettings.apply(context)
            }
        }
    }
}
