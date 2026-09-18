package com.nicobrailo.alauncher.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.nicobrailo.alauncher.R
import com.nicobrailo.alauncher.SlideshowActivity

// Draws a home button on top of another app, for apps that leave the user
// stranded: WhatsApp's own header hides the Portal's Back/Home bar, and then
// there is no way back to the launcher (the Portal has no home key).
//
// It's shown only for the apps the user picked in the app list's long-press
// menu, and only while one of them is in front: AppListActivity starts it when
// it launches such an app, and SlideshowActivity stops it as soon as the
// launcher is back on screen.
class HomeButtonService : Service() {
    private var button: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE || !AndroidSettings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A service that outlives the launcher's own screen has to be in the
        // foreground, which means a notification
        startForeground(NOTIFICATION_ID, notification())
        showButton()
        // Not sticky: if the system kills us, the app we were covering is gone
        // too, and a button over the wrong app would be worse than none
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideButton()
        super.onDestroy()
    }

    private fun showButton() {
        if (button != null) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.home_button, null)
        view.setOnClickListener {
            startActivity(
                Intent(this, SlideshowActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            stopSelf()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Only the button itself takes touches; the app underneath keeps
            // working normally
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = MARGIN_PX
            y = MARGIN_PX
        }
        try {
            windowManager.addView(view, params)
            button = view
        } catch (e: WindowManager.BadTokenException) {
            // The permission was revoked since it was last checked
            Log.w(TAG, "Can't show the home button", e)
            stopSelf()
        }
    }

    private fun hideButton() {
        val view = button ?: return
        button = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Home button was already gone", e)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.home_button_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager?.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.home_button_notification))
            .setSmallIcon(R.drawable.ic_home)
            .build()
    }

    companion object {
        private const val TAG = "HomeButtonService"
        private const val CHANNEL_ID = "home_button"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HIDE = "hide"
        private const val MARGIN_PX = 16

        // Shown while an app the user flagged is in front
        fun show(context: Context) {
            if (!AndroidSettings.canDrawOverlays(context)) return
            context.startService(Intent(context, HomeButtonService::class.java))
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, HomeButtonService::class.java).setAction(ACTION_HIDE)
            )
        }
    }
}
