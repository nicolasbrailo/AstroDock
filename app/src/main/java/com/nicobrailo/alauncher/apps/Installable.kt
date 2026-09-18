package com.nicobrailo.alauncher.apps

import android.content.Context
import android.content.pm.PackageManager

// An app worth having on the Portal, which has no app store of its own.
//
// apkUrl is a URL that always serves the current release. Where a project has
// no such URL (most do not: their download links carry a version number), it is
// null and the user is sent to pageUrl in the browser to download it there.
data class Installable(
    val name: String,
    val packageName: String,
    val description: String,
    val apkUrl: String?,
    val pageUrl: String,
) {
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

// The apps offered in the Apps tab of the settings
val INSTALLABLE_APPS = listOf(
    Installable(
        name = "F-Droid",
        packageName = "org.fdroid.fdroid",
        description = "App store for free and open source apps. The easiest way to install and " +
            "update everything else on a device without Google Play.",
        // F-Droid keeps this URL pointing at the current release
        apkUrl = "https://f-droid.org/F-Droid.apk",
        pageUrl = "https://f-droid.org/",
    ),
    Installable(
        name = "Jellyfin",
        packageName = "org.jellyfin.mobile",
        description = "Plays music and video from a Jellyfin server. Its downloads are numbered " +
            "per release, so this opens the download page.",
        apkUrl = null,
        pageUrl = "https://f-droid.org/packages/org.jellyfin.mobile/",
    ),
)
