package com.nicobrailo.astrodock

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

// Device admin with the force-lock policy only (res/xml/device_admin.xml), so
// the app can turn the screen off with DevicePolicyManager.lockNow(). The user
// activates it from the system tab of the settings.
class ScreenAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, ScreenAdminReceiver::class.java)
    }
}
