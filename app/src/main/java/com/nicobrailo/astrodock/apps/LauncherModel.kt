package com.nicobrailo.astrodock.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// One launchable app, from LauncherApps (not queryIntentActivities): that way
// work-profile and cloned apps are included, their icons carry the profile
// badge, and the system tells us when apps are installed or removed.
data class LauncherApp(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: Drawable,
    val isSystem: Boolean, // Can't be uninstalled, only disabled
    val isOwnProfile: Boolean,
    // The app asks for dark status bar icons. The Portal draws its Back and
    // Home buttons in white and doesn't darken them, so they become invisible
    // and untappable over such an app, leaving no way back to the launcher
    // (see HomeButtonService).
    val wantsLightStatusBar: Boolean,
) {
    // Identifies the app in a folder. The user is included so the same app in a
    // work profile is a different entry.
    val key: String get() = "${component.flattenToString()}@${user.hashCode()}"
}

// A shortcut another app pinned to the home screen, such as a web page Firefox
// was asked to add ("Add to Home screen", or "Install" for a site that can be
// installed as an app). Android keeps them, not us: PinShortcutActivity accepts
// the request, and LauncherModel lists them. Only the default home app may read
// or start them, so anywhere else there are none.
data class PinnedShortcut(
    val label: String,
    val icon: Drawable?,
    val info: ShortcutInfo,
) {
    val packageName: String get() = info.`package`

    // Identifies the shortcut in a folder, next to the apps' keys (see
    // LauncherApp.key); the prefix keeps the two from ever meaning the same
    val key: String get() = "$KEY_PREFIX${info.`package`}/${info.id}@${info.userHandle.hashCode()}"

    companion object {
        const val KEY_PREFIX = "shortcut:"
    }
}

// The installed apps, kept up to date. start() begins listening, stop() ends;
// onChanged is called on the main thread whenever the app list changes.
class LauncherModel(private val context: Context, private val onChanged: () -> Unit) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = onChanged()
        override fun onPackageAdded(packageName: String, user: UserHandle) = onChanged()
        override fun onPackageChanged(packageName: String, user: UserHandle) = onChanged()
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChanged()
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChanged()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) =
            onChanged()
    }

    fun start() = launcherApps?.registerCallback(callback) ?: Unit

    fun stop() = launcherApps?.unregisterCallback(callback) ?: Unit

    // Every launchable app in every profile, except this launcher, sorted by
    // name. Loading labels and icons reads each app's resources, so not on the
    // main thread.
    suspend fun apps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
        profiles.flatMap { user ->
            launcherApps?.getActivityList(null, user).orEmpty().map { toApp(it, user) }
        }
            .filterNot { it.component.packageName == context.packageName }
            .sortedBy { it.label.lowercase() }
            .also { apps ->
                val light = apps.filter { it.wantsLightStatusBar }.map { it.label }
                Log.i(TAG, "Apps that hide the Portal's Back/Home buttons: $light")
            }
    }

    private fun toApp(info: LauncherActivityInfo, user: UserHandle): LauncherApp {
        val flags = info.applicationInfo.flags
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        return LauncherApp(
            label = info.label.toString(),
            component = info.componentName,
            user = user,
            // Badged: work-profile apps get the briefcase marker
            icon = info.getBadgedIcon(0),
            isSystem = system,
            isOwnProfile = user == Process.myUserHandle(),
            wantsLightStatusBar = wantsLightStatusBar(info),
        )
    }

    // Reads the app's own theme, since its windows don't exist yet. Apps that
    // ask for it in code instead are missed, and can be switched on by hand in
    // the long-press menu.
    private fun wantsLightStatusBar(info: LauncherActivityInfo): Boolean {
        val packageName = info.componentName.packageName
        return try {
            val appContext = context.createPackageContext(packageName, 0)
            // LauncherActivityInfo.getActivityInfo() only exists from API 31
            val activityInfo = context.packageManager.getActivityInfo(info.componentName, 0)
            val themeId = activityInfo.themeResource.takeIf { it != 0 }
                ?: info.applicationInfo.theme
            if (themeId == 0) return false
            val theme = appContext.resources.newTheme()
            theme.applyStyle(themeId, true)
            val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
            try {
                attrs.getBoolean(0, false)
            } finally {
                attrs.recycle()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Can't read the theme of $packageName", e)
            false
        } catch (e: RuntimeException) {
            // A broken or unreadable resource table shouldn't drop the app
            Log.w(TAG, "Can't read the theme of $packageName", e)
            false
        }
    }

    // The shortcuts pinned to us, in every profile, sorted by name. Empty
    // unless this is the default home app, the only one Android lets read them.
    suspend fun shortcuts(): List<PinnedShortcut> = withContext(Dispatchers.IO) {
        val launcherApps = launcherApps ?: return@withContext emptyList()
        if (!canReadShortcuts()) return@withContext emptyList()
        val density = context.resources.displayMetrics.densityDpi
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
        profiles.flatMap { user ->
            try {
                launcherApps.getShortcuts(query, user).orEmpty()
            } catch (e: IllegalStateException) {
                // A profile that is locked or not running has nothing to show
                Log.i(TAG, "Can't read the shortcuts of $user: ${e.message}")
                emptyList()
            }
        }
            // An app disables a shortcut it can't honour any more (the page
            // or the app it pointed at is gone), but it stays pinned
            .filter { it.isEnabled }
            .map { info ->
                PinnedShortcut(
                    label = (info.shortLabel ?: info.longLabel ?: info.`package`).toString(),
                    icon = launcherApps.getShortcutBadgedIconDrawable(info, density),
                    info = info,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    // Only the default home app can. Without it the list of shortcuts is empty
    // whether or not any are pinned, so it says nothing about which are gone.
    fun canReadShortcuts(): Boolean = try {
        launcherApps?.hasShortcutHostPermission() == true
    } catch (e: IllegalStateException) {
        false
    }

    // Opens a shortcut: for a web page, the browser that pinned it, at that
    // page. Returns whether it did.
    fun launch(shortcut: PinnedShortcut): Boolean {
        val launcherApps = launcherApps ?: return false
        return try {
            launcherApps.startShortcut(shortcut.info, null, null)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't open the shortcut ${shortcut.label}", e)
            false
        } catch (e: IllegalStateException) {
            // The profile it belongs to is locked
            Log.w(TAG, "Can't open the shortcut ${shortcut.label}", e)
            false
        } catch (e: SecurityException) {
            // No longer the default home app
            Log.w(TAG, "Can't open the shortcut ${shortcut.label}", e)
            false
        }
    }

    // Unpins a shortcut. Android only takes the whole list of an app's pinned
    // shortcuts, so this pins the ones that stay.
    fun unpin(shortcut: PinnedShortcut) {
        val launcherApps = launcherApps ?: return
        val info = shortcut.info
        try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(info.`package`)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val remaining = launcherApps.getShortcuts(query, info.userHandle).orEmpty()
                .map { it.id }
                .filter { it != info.id }
            launcherApps.pinShortcuts(info.`package`, remaining, info.userHandle)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Can't unpin ${shortcut.label}", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't unpin ${shortcut.label}", e)
        }
    }

    private companion object {
        const val TAG = "LauncherModel"
    }

    fun launch(app: LauncherApp) {
        launcherApps?.startMainActivity(app.component, app.user, null, null)
    }

    fun showAppInfo(app: LauncherApp) {
        launcherApps?.startAppDetailsActivity(app.component, app.user, null, null)
    }
}
