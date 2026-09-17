package com.nicobrailo.alauncher

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

// The home screen: a full screen slideshow of random Immich pictures, driven by
// SlideshowController. Tapping it opens AppListActivity. The same slideshow
// runs as the screensaver in SlideshowDreamService.
class SlideshowActivity : AppCompatActivity() {
    private lateinit var slideshow: SlideshowController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.slideshow)
        val root = findViewById<View>(R.id.root)
        hideSystemBars()

        slideshow = SlideshowController(this, root, lifecycleScope, interactive = true) {
            startActivity(Intent(this, AppListActivity::class.java))
            @Suppress("DEPRECATION") // The replacement needs API 34
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onStart() {
        super.onStart()
        slideshow.start()
    }

    // The system shows the bars again whenever the window loses focus
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        slideshow.stop()
        super.onStop()
    }
}
