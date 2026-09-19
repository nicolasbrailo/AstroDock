package com.nicobrailo.astrodock

import android.service.dreams.DreamService
import android.view.MotionEvent
import android.view.View
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// The same slideshow as SlideshowActivity, as the system screensaver.
//
// This is how the Portal shows an idle screen: when its camera sees someone it
// wakes the screen and starts the screensaver, and when nobody is around the
// device sleeps (see the Portal notes in AGENTS.md). Set it as the screensaver
// from the system settings tab, or with tools/setup-device.sh.
//
// It isn't interactive, so a touch ends the screensaver and goes back to the
// home screen, which is SlideshowActivity showing the same thing.
class SlideshowDreamService : DreamService() {
    private var scope: CoroutineScope? = null
    private var slideshow: SlideshowController? = null

    // Before the dream's window is created, so the window is built with these
    // (setting isInteractive in onAttachedToWindow leaves the window focusable,
    // and it then swallows touches instead of the screensaver ending)
    override fun onCreate() {
        super.onCreate()
        isFullscreen = true
        isInteractive = false
        isScreenBright = true
    }

    // Any touch ends the screensaver and goes back to the home screen
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Counts as the user being there, so the night rule holds off
        SlideshowState.shared.noteTouch()
        wakeUp()
        return super.dispatchTouchEvent(event)
    }

    // R.layout.slideshow is shared with SlideshowActivity, but a dream has no
    // AppCompat theme: it can only use framework attributes (?android:attr/...),
    // and anything else fails to inflate and takes the screensaver down with it
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Same layout rules as the home screen's window, so the picture is in
        // exactly the same place in both and the handover doesn't shift it
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.slideshow)

        val root = findViewById<View>(R.id.root)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        slideshow = SlideshowController(this, root, newScope, interactive = false) {}
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        slideshow?.start()
    }

    override fun onDreamingStopped() {
        slideshow?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        slideshow?.stop()
        slideshow = null
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }
}
