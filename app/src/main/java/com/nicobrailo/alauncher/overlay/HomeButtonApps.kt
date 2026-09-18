package com.nicobrailo.alauncher.overlay

import android.content.Context

// The apps that get a home button drawn over them (see HomeButtonService).
// Stored by package name, so it survives the app being updated.
class HomeButtonApps(context: Context) {
    private val prefs = context.getSharedPreferences("home_button_apps", Context.MODE_PRIVATE)

    fun isEnabled(packageName: String): Boolean = packageName in packages()

    fun setEnabled(packageName: String, enabled: Boolean) {
        val updated = packages().toMutableSet()
        if (enabled) updated += packageName else updated -= packageName
        prefs.edit().putStringSet(KEY, updated).apply()
    }

    private fun packages(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    private companion object {
        const val KEY = "packages"
    }
}
