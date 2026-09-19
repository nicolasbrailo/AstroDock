package com.nicobrailo.astrodock

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Hides the status and navigation bars, leaving the picture full screen. A
// swipe from the edge brings them back for a few seconds.
//
// The system clears these flags whenever the window loses focus (opening the
// app list or the settings, or the screensaver starting), so activities call
// this again every time they get focus back, not just once at startup.
fun Activity.hideSystemBars() {
    // Lay the content out at full size whether or not the bars are showing, so
    // the picture doesn't shrink and jump when they briefly appear
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}
