package com.nicobrailo.astrodock.apps

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
    val githubRepo: String? = null,
    // The release assets to look for, best first, for an app updated from its
    // own GitHub releases. The name is looked up in the release rather than
    // put in apkUrl, because GitHub's /releases/latest/download/<name> URL
    // redirects to the release whether or not it holds a file by that name, so
    // a wrong name isn't found out until the download has already failed.
    val githubAssets: List<String> = emptyList(),
    // For a project that publishes its current version rather than a URL that
    // always serves it, as Mozilla does: the APK URL is built from the version
    val versionFeed: VersionFeed? = null,
) {
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

// Where a project says what its current version is: a JSON object at `url`
// with the version under `key`, and how to get from that to the APK
data class VersionFeed(val url: String, val key: String, val apkUrl: (String) -> String)

// Whether a version read from a feed is only digits and dots, so that it can be
// put in a URL path without turning it into some other path
fun isPlainVersion(version: String): Boolean = Regex("""\d+(\.\d+)*""").matches(version)

// Mozilla keeps every Firefox for Android release in its archive, at a path
// made of the version. Only the arm64 build: that is what the Portal is, and
// Mozilla doesn't publish one APK for every architecture.
fun firefoxApkUrl(version: String): String =
    "https://archive.mozilla.org/pub/fenix/releases/$version/android/" +
        "fenix-$version-android-arm64-v8a/fenix-$version.multi.android-arm64-v8a.apk"

// Picks which of a release's files to download: the first name the app asked
// for that the release actually has, or else any APK in it, so that a release
// whose files were renamed still updates the app instead of failing.
fun pickApkAsset(available: List<String>, preferred: List<String>): String? =
    preferred.firstOrNull { it in available }
        ?: available.firstOrNull { it.endsWith(".apk", ignoreCase = true) }

// Whether a release's file is the one already installed, from the digest
// GitHub publishes for it ("sha256:<hex>") and the installed APK's own hash.
// This is what decides whether there's an update: the APK's version name is
// inside the APK, so nothing short of downloading it would reveal that, and a
// release's tag only says anything if it's written like the version name.
// Releases from before GitHub published digests have none, and then there is
// nothing to compare, so this says so instead of guessing.
fun sameBuild(assetDigest: String?, installedSha256: String): Boolean? {
    val prefix = "sha256:"
    if (assetDigest == null || !assetDigest.startsWith(prefix, ignoreCase = true)) return null
    val hex = assetDigest.substring(prefix.length)
    if (hex.isEmpty()) return null
    return hex.equals(installedSha256, ignoreCase = true)
}

// The apps offered in the Apps tab of the settings
val INSTALLABLE_APPS = listOf(
    Installable(
        name = "AstroDock",
        packageName = "com.nicobrailo.astrodock",
        description = "This home screen replacement app. Check for updates and install them directly from GitHub releases.",
        // Resolved from the latest release, see githubAssets
        apkUrl = null,
        pageUrl = "https://github.com/nicolasbrailo/AstroDock",
        githubRepo = "nicolasbrailo/AstroDock",
        // A debug build first: tools/push-config.sh and tools/force-uninstall.sh
        // both go through run-as, which only works on a debuggable app. The
        // release so far is named AstroDock.apk, and app-debug/app-release are
        // what Gradle calls its output.
        // tools/build-apks.sh builds the first and the fourth of these
        githubAssets = listOf(
            "AstroDock-debug.apk", "app-debug.apk",
            "AstroDock.apk", "AstroDock-release.apk", "app-release.apk",
        ),
    ),
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
    Installable(
        name = "WhatsApp",
        packageName = "com.whatsapp",
        description = "Messages and calls. Downloaded from whatsapp.com, which is a large " +
            "download (about 150 MB).",
        // whatsapp.com serves the current release from this URL, as an APK
        apkUrl = "https://www.whatsapp.com/android/current/WhatsApp.apk",
        pageUrl = "https://www.whatsapp.com/download/android",
    ),
    Installable(
        name = "Firefox",
        packageName = "org.mozilla.firefox",
        description = "Web browser. Downloaded from Mozilla's archive: the arm64 build of the " +
            "current stable release, which is a large download (about 130 MB).",
        // Mozilla has no URL that always serves the current Android release
        // (download.mozilla.org only knows the desktop ones), but it does
        // publish the version, next to the beta and nightly ones
        apkUrl = null,
        pageUrl = "https://archive.mozilla.org/pub/fenix/releases/",
        versionFeed = VersionFeed(
            url = "https://product-details.mozilla.org/1.0/mobile_versions.json",
            key = "version",
            apkUrl = ::firefoxApkUrl,
        ),
    ),
    Installable(
        name = "Spotify",
        packageName = "com.spotify.music",
        description = "Music streaming. Spotify only publishes the app through Google Play, so " +
            "this opens APKPure, which mirrors it.",
        // No official APK: spotify.com only links to Google Play
        apkUrl = null,
        pageUrl = "https://apkpure.com/spotify-app/com.spotify.music/download",
    ),
)
