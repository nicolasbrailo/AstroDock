package com.nicobrailo.astrodock.overlay

import android.content.Context

// Which apps get a home button drawn over them (see HomeButtonService).
//
// By default the launcher decides: apps that ask for dark status bar icons lose
// the Portal's Back and Home buttons, so they get one. The user can override
// that either way from the app list's long-press menu, and the choice is kept
// by package name so it survives the app being updated.
class HomeButtonApps(context: Context) {
    private val prefs = context.getSharedPreferences("home_button_apps", Context.MODE_PRIVATE)

    // `detected` is what the launcher worked out on its own
    // (LauncherApp.wantsLightStatusBar)
    fun shouldShow(packageName: String, detected: Boolean): Boolean =
        override(packageName) ?: (packageName in ALWAYS || detected)

    // What the user asked for, if they asked at all. It is kept apart from the
    // rest of the decision because their choice wins over both ALWAYS and the
    // detection.
    fun override(packageName: String): Boolean? = when {
        packageName in packages(ON) -> true
        packageName in packages(OFF) -> false
        else -> null
    }

    fun setShown(packageName: String, shown: Boolean) {
        prefs.edit()
            .putStringSet(ON, packages(ON).toMutableSet().apply { if (shown) add(packageName) else remove(packageName) })
            .putStringSet(OFF, packages(OFF).toMutableSet().apply { if (shown) remove(packageName) else add(packageName) })
            .apply()
    }

    private fun packages(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty()

    private companion object {
        const val ON = "packages"
        const val OFF = "packages_off"

        // Apps the detection provably can't catch, because they set the light
        // status bar in code and their manifest theme says nothing. WhatsApp is
        // the app the whole feature was written for, and without this it needed
        // the long-press menu on every device, which is easy to forget and is
        // lost whenever the app's data is cleared.
        val ALWAYS = setOf("com.whatsapp")
    }
}
