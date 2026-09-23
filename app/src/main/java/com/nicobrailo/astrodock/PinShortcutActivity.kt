package com.nicobrailo.astrodock

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import android.widget.Toast

// Accepts a shortcut another app asks to pin to the home screen, such as
// Firefox's "Add to Home screen". Android only lets an app ask when the default
// home app has an activity for CONFIRM_PIN_SHORTCUT, so without this one the
// option is missing or fails in the browser.
//
// There's nothing to confirm: the user asked for it in the other app a moment
// ago, and Android's own dialog there already said what it would add. So it is
// accepted as it comes, and this activity never draws anything but the toast.
// Where the shortcut then shows is up to AppListActivity and the slideshow,
// which list what's pinned (see LauncherModel.shortcuts).
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        // Null unless the intent really came from the system with a request in
        // it, since this activity has to be exported for the system to reach it
        val request = launcherApps?.getPinItemRequest(intent)
        if (request != null && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
            request.isValid
        ) {
            val label = request.shortcutInfo?.shortLabel ?: ""
            if (request.accept()) {
                Log.i(TAG, "Pinned \"$label\" from ${request.shortcutInfo?.`package`}")
                Toast.makeText(this, getString(R.string.shortcut_pinned, label), Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "The pin request for \"$label\" was no longer valid")
            }
        } else {
            Log.w(TAG, "Not a shortcut pin request: $intent")
        }
        finish()
    }

    private companion object {
        const val TAG = "PinShortcutActivity"
    }
}
