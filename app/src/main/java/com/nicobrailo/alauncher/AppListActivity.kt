package com.nicobrailo.alauncher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Grid of the installed apps (anything with a launcher icon, except this app).
// Tapping one launches it; the button in the corner opens SettingsActivity.
// Opened by tapping the slideshow. It closes when an app is launched, so
// coming back lands on the slideshow.
class AppListActivity : AppCompatActivity() {
    private data class App(val label: String, val component: ComponentName, val icon: Drawable)

    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val apps = findViewById<RecyclerView>(R.id.apps)
        val columnWidthPx = resources.displayMetrics.density * COLUMN_WIDTH_DP
        val columns = (resources.displayMetrics.widthPixels / columnWidthPx).toInt().coerceAtLeast(1)
        apps.layoutManager = GridLayoutManager(this, columns)
        apps.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        // Reloaded every time, so installed and removed apps show up
        lifecycleScope.launch { adapter.submit(loadApps()) }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") // The replacement needs API 34
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // Loading labels and icons reads every app's resources, so not on the main thread
    private suspend fun loadApps(): List<App> = withContext(Dispatchers.Default) {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val info = it.activityInfo
                App(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(info.packageName, info.name),
                    icon = it.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun launch(app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            finish()
        } catch (e: ActivityNotFoundException) {
            // Uninstalled since the list was loaded
            Log.w(TAG, "Can't launch ${app.component}", e)
            lifecycleScope.launch { adapter.submit(loadApps()) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't launch ${app.component}", e)
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var apps: List<App> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val label: TextView = view.findViewById(R.id.label)
        }

        @Suppress("NotifyDataSetChanged") // The whole list is replaced, and it's small
        fun submit(newApps: List<App>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.itemView.setOnClickListener { launch(app) }
        }
    }

    private companion object {
        const val TAG = "AppListActivity"
        const val COLUMN_WIDTH_DP = 140
    }
}
