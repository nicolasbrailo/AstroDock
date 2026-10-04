package com.nicobrailo.astrodock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// The alarms (AlarmsFragment), as an app of their own in the app list rather
// than a tab of the settings: they're something to set every so often, like
// a clock app's, and the settings are for setting the device up. It is the
// one activity of ours the app list shows (LauncherModel.apps).
class AlarmsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.alarms_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().add(android.R.id.content, AlarmsFragment()).commit()
        }
    }
}
