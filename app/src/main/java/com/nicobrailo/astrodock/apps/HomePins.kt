package com.nicobrailo.astrodock.apps

import android.content.Context

// The apps the user pinned to the home screen from the app list's long-press
// menu, by LauncherApp.key, so the same app in a work profile is pinned on its
// own. The home screen shows them next to the shortcuts other apps pinned,
// which Android keeps for us; nothing does that for an app, so these are ours.
// An app that is uninstalled stays here, and comes back if it's installed again.
class HomePins(context: Context) {
    private val prefs = context.getSharedPreferences("home_pins", Context.MODE_PRIVATE)

    fun keys(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    fun isPinned(app: LauncherApp): Boolean = app.key in keys()

    fun setPinned(app: LauncherApp, pinned: Boolean) {
        // The set getStringSet returns must not be changed in place
        val keys = keys().toMutableSet().apply { if (pinned) add(app.key) else remove(app.key) }
        prefs.edit().putStringSet(KEY, keys).apply()
    }

    private companion object {
        const val KEY = "apps"
    }
}
