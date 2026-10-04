package com.nicobrailo.astrodock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.nicobrailo.astrodock.alarm.AlarmReceiver

// Puts things back after this app is updated, which an update done on the
// device itself (the Apps tab) has no adb to do.
//
// Replacing the package kills it. If that happens while our home screen is in
// front, the system restarts it straight away, finds the package still frozen
// by the install, and falls back to the Portal's own home screen, which then
// starts our screensaver and stops it again about every second, until someone
// presses Home. A Portal has no Home key. Measured 2026-09-30 with
// `adb install -r`. So once the new version is in, this presses Home for it.
//
// Installing over a running screensaver can also make the system put the
// Portal's own screensaver back (measured 2026-09-25). That is undone too, but
// only when ours was the screensaver when the app last ran, so a screensaver
// the user chose is left alone.
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        restoreScreensaver(context)
        // An update may or may not keep the system's alarms; asking again is free
        AlarmReceiver.schedule(context)
        goHome(context)
    }

    private fun goHome(context: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.packageName != context.packageName) {
            Log.i(TAG, "Updated, but not the home screen, so leaving the screen alone")
            return
        }
        // Starting an activity from the background needs the "display over
        // other apps" permission from Android 10 on, which the System tab asks
        // for and tools/setup-device.sh grants
        try {
            context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "Updated, back to the home screen")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Updated, but can't go back to the home screen", e)
        }
    }

    private fun restoreScreensaver(context: Context) {
        val now = AndroidSettings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS)
        if (!prefs(context).getBoolean(KEY_WAS_OURS, false) || isOurs(context, now)) return
        if (now != PORTAL_SCREENSAVER) {
            Log.i(TAG, "The screensaver is $now now, leaving it")
            return
        }
        if (!ScreenControl.canWriteSecureSettings(context)) {
            Log.w(TAG, "The update put the Portal's screensaver back, and there's no grant to undo it")
            return
        }
        try {
            AndroidSettings.Secure.putString(
                context.contentResolver,
                SCREENSAVER_COMPONENTS,
                ComponentName(context, SlideshowDreamService::class.java).flattenToShortString(),
            )
            Log.i(TAG, "The update put the Portal's screensaver back, restored ours")
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't write $SCREENSAVER_COMPONENTS", e)
        }
    }

    companion object {
        private const val TAG = "UpdateReceiver"

        // Hidden framework constant (Settings.Secure.SCREENSAVER_COMPONENTS)
        private const val SCREENSAVER_COMPONENTS = "screensaver_components"
        private const val PORTAL_SCREENSAVER =
            "com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService"
        private const val KEY_WAS_OURS = "screensaver_was_ours"

        private fun prefs(context: Context) =
            context.getSharedPreferences("update_receiver", Context.MODE_PRIVATE)

        // The setting can hold several components, comma separated, and each
        // either spelled out or with the package left implicit
        private fun isOurs(context: Context, setting: String?): Boolean {
            val ours = ComponentName(context, SlideshowDreamService::class.java)
            return setting.orEmpty().split(',').any { ComponentName.unflattenFromString(it.trim()) == ours }
        }

        // Called whenever a slideshow appears, so that after an update we know
        // whether the screensaver the system left was a choice or the install
        fun noteScreensaver(context: Context) {
            val now = AndroidSettings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS)
            prefs(context).edit().putBoolean(KEY_WAS_OURS, isOurs(context, now)).apply()
        }
    }
}
