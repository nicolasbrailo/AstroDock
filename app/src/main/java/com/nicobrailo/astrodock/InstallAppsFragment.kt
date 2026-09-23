package com.nicobrailo.astrodock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nicobrailo.astrodock.apps.ApkInstaller
import com.nicobrailo.astrodock.apps.INSTALLABLE_APPS
import com.nicobrailo.astrodock.apps.Installable
import com.nicobrailo.astrodock.apps.VersionFeed
import com.nicobrailo.astrodock.apps.isPlainVersion
import com.nicobrailo.astrodock.apps.pickApkAsset
import com.nicobrailo.astrodock.apps.sameBuild
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import com.nicobrailo.astrodock.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// Apps worth having on a Portal, which has no app store. Each row says whether
// the app is installed and offers the quickest way to get it: downloading the
// APK when the project publishes a URL that always serves the current release,
// or the download page in a browser when it doesn't (see Installable).
//
// The rows are the same layout as the System tab.
class InstallAppsFragment : Fragment() {
    private lateinit var items: LinearLayout
    private lateinit var installer: ApkInstaller

    // Apps being downloaded right now, so their rows aren't replaced underneath
    private val downloading = mutableSetOf<String>()

    // Held from the start of a download until the user is back from the system
    // installer, so the screensaver doesn't cover the install (see
    // ScreenControl.keepScreenOn)
    private var awake: PowerManager.WakeLock? = null
    private var waitingForInstaller = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_system_settings, container, false)
        items = view.findViewById(R.id.items)
        installer = ApkInstaller(requireContext())
        return view
    }

    // Rebuilt every time, so an app installed meanwhile shows as installed
    override fun onResume() {
        super.onResume()
        // Back from the installer, whether the app was installed or not
        if (waitingForInstaller) {
            waitingForInstaller = false
            letScreenSleep()
        }
        if (downloading.isEmpty()) show()
    }

    override fun onDestroyView() {
        letScreenSleep()
        super.onDestroyView()
    }

    private fun letScreenSleep() {
        ScreenControl.release(awake)
        awake = null
    }

    private fun show() {
        items.removeAllViews()
        for (app in INSTALLABLE_APPS) items.addView(row(app))
    }

    private fun row(app: Installable): View {
        val view = layoutInflater.inflate(R.layout.item_system_setting, items, false)
        val status = view.findViewById<TextView>(R.id.status)
        val button = view.findViewById<Button>(R.id.action)
        view.findViewById<TextView>(R.id.title).text = app.name
        view.findViewById<TextView>(R.id.description).text = app.description

        val installed = app.isInstalled(requireContext())
        // The download is no use once the app is there, unless the installer is
        // reading it at this very moment: AstroDock updating itself counts as
        // installed the whole way through, and deleting the file from under the
        // installer fails the install with "There was a problem while parsing
        // the package". It's deleted when the user comes back instead.
        if (installed && !waitingForInstaller) installer.forget(app)

        if (installed && app.githubRepo != null) {
            status.text = getString(R.string.install_current_version, BuildConfig.VERSION_NAME)
            // Checked when the user asks, not on every visit to the tab: it's
            // a request to GitHub and a hash of the whole APK
            button.setText(R.string.install_button_check_update)
            button.setOnClickListener {
                button.isEnabled = false
                status.setText(R.string.install_checking_update)
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val (release, installedSha256) = withContext(Dispatchers.IO) {
                            latestRelease(app) to installedApkSha256(app)
                        }
                        // The digest settles it. Only a release published before
                        // GitHub started giving one falls back to the tag, which
                        // is right only as far as the tag is written like the
                        // version name; ignoring case, because a tag is written
                        // however whoever cut the release felt like writing it.
                        Log.d(TAG, "${app.name} ${release.tag}: installed is $installedSha256, " +
                            "the release has ${release.digest}")
                        val current = sameBuild(release.digest, installedSha256)
                            ?: release.tag.trimStart('v', 'V')
                                .equals(BuildConfig.VERSION_NAME, ignoreCase = true)
                        button.isEnabled = true
                        when {
                            current -> {
                                status.setText(R.string.install_no_update)
                                button.setText(R.string.install_button_open)
                                button.setOnClickListener { open(app) }
                            }
                            // Another build, but nothing in it we can install
                            release.apkUrl == null -> {
                                status.text = getString(R.string.install_update_available, release.tag)
                                button.setText(R.string.install_button_page)
                                button.setOnClickListener { openInBrowser(app.pageUrl) }
                            }
                            else -> {
                                status.text = getString(R.string.install_update_available, release.tag)
                                button.setText(R.string.install_button_install)
                                button.setOnClickListener {
                                    download(app.copy(apkUrl = release.apkUrl), status, button)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Can't check updates for ${app.name}", e)
                        status.text = getString(R.string.install_failed, e.message)
                        button.isEnabled = true
                        button.setOnClickListener { open(app) }
                    }
                }
            }
        } else if (app.versionFeed != null) {
            versionFeedRow(app, app.versionFeed, installed, status, button)
        } else {
            status.setText(if (installed) R.string.install_installed else R.string.install_not_installed)

            when {
                installed -> {
                    button.setText(R.string.install_button_open)
                    button.setOnClickListener { open(app) }
                }
                app.apkUrl != null -> {
                    button.setText(R.string.install_button_install)
                    button.setOnClickListener { download(app, status, button) }
                }
                else -> {
                    button.setText(R.string.install_button_page)
                    button.setOnClickListener { openInBrowser(app.pageUrl) }
                }
            }
        }
        return view
    }

    // A row for an app whose current version is published (see VersionFeed).
    // Unlike a GitHub release, that is checked every time the tab is shown
    // rather than when asked: it is one small request, with nothing to hash.
    // Until it answers, or if it fails, the row offers what it would without
    // the feed.
    private fun versionFeedRow(
        app: Installable, feed: VersionFeed, installed: Boolean, status: TextView, button: Button,
    ) {
        val installedVersion = if (installed) installedVersion(app) else null
        if (installed) {
            status.text = installedVersion?.let { getString(R.string.install_current_version, it) }
                ?: getString(R.string.install_installed)
            button.setText(R.string.install_button_open)
            button.setOnClickListener { open(app) }
        } else {
            status.setText(R.string.install_not_installed)
            button.setText(R.string.install_button_page)
            button.setOnClickListener { openInBrowser(app.pageUrl) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { latestVersion(feed) }
            } catch (e: Exception) {
                Log.w(TAG, "Can't read the current version of ${app.name}", e)
                return@launch
            }
            Log.d(TAG, "${app.name}: installed is $installedVersion, the current one is $latest")
            when {
                installedVersion == latest -> {
                    status.text = getString(R.string.install_up_to_date_version, latest)
                    return@launch
                }
                installed -> status.text = getString(R.string.install_update_available, latest)
                else -> status.text = getString(R.string.install_not_installed_latest, latest)
            }
            button.setText(R.string.install_button_install)
            button.setOnClickListener { download(app.copy(apkUrl = feed.apkUrl(latest)), status, button) }
        }
    }

    private fun installedVersion(app: Installable): String? = try {
        requireContext().packageManager.getPackageInfo(app.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun latestVersion(feed: VersionFeed): String {
        val req = Request.Builder().url(feed.url).header("Accept", "application/json").build()
        return installer.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val version = JSONObject(resp.body.string()).optString(feed.key)
            if (!isPlainVersion(version)) throw IOException("Unexpected version \"$version\"")
            version
        }
    }

    // What the latest release offers: its tag, the APK to download from it (null
    // when it holds none) and the digest GitHub has for that file.
    private data class Release(val tag: String, val apkUrl: String?, val digest: String?)

    // The installed APK's hash, for the digest of the release's file. The APK
    // is tens of megabytes, so this is only ever called off the main thread.
    private fun installedApkSha256(app: Installable): String {
        val info = requireContext().packageManager.getApplicationInfo(app.packageName, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        File(info.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // The latest release. Reading the file's name from it is what makes this
    // reliable:
    // https://github.com/<repo>/releases/latest/download/<name> is a stable URL,
    // but it redirects to the newest release whether or not that release has a
    // file by that name, so a name that's wrong (or that changes between
    // releases) looks fine until the download 404s.
    private fun latestRelease(app: Installable): Release {
        val url = "https://api.github.com/repos/${app.githubRepo}/releases/latest"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        return installer.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val release = JSONObject(resp.body.string())
            val assets = release.optJSONArray("assets")
            // In the order the release lists them, which is what pickApkAsset
            // falls back to
            val urls = LinkedHashMap<String, String>()
            val digests = HashMap<String, String>()
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                val name = asset.optString("name")
                val download = asset.optString("browser_download_url")
                if (name.isEmpty() || download.isEmpty()) continue
                urls[name] = download
                val digest = asset.optString("digest")
                if (digest.isNotEmpty()) digests[name] = digest
            }
            val name = pickApkAsset(urls.keys.toList(), app.githubAssets)
            Release(release.optString("tag_name", ""), urls[name], digests[name])
        }
    }

    private fun open(app: Installable) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            // Installed but with nothing to launch, e.g. a background-only app
            show()
            return
        }
        startActivity(intent)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't open $url", e)
            Toast.makeText(requireContext(), getString(R.string.install_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun download(app: Installable, status: TextView, button: Button) {
        if (!installer.canInstall) {
            // Android refuses the install otherwise, and only the user can
            // change that
            Toast.makeText(requireContext(), R.string.install_needs_permission, Toast.LENGTH_LONG).show()
            installer.askToAllowInstalls()
            return
        }
        button.isEnabled = false
        downloading += app.packageName
        awake = ScreenControl.keepScreenOn(requireContext(), "install", KEEP_AWAKE_MILLIS)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = installer.download(app) { percent ->
                    // The download runs on an IO thread, and only the main one
                    // may touch a view. Android 10 logs this and carries on,
                    // but says it will throw in a later version.
                    withContext(Dispatchers.Main) {
                        status.text = if (percent < 0) {
                            getString(R.string.install_downloading_unknown)
                        } else {
                            getString(R.string.install_downloading, percent)
                        }
                    }
                }
                // The system takes over from here and asks the user to confirm.
                // The screen is kept on until we're resumed again.
                waitingForInstaller = true
                installer.install(file)
            } catch (e: IOException) {
                Log.w(TAG, "Can't download ${app.name}", e)
                status.text = getString(R.string.install_failed, e.message)
                Toast.makeText(requireContext(), status.text, Toast.LENGTH_LONG).show()
            } catch (e: ActivityNotFoundException) {
                // No package installer at all: nothing sensible to fall back to
                Log.w(TAG, "Can't install ${app.name}", e)
                openInBrowser(app.pageUrl)
            } finally {
                downloading -= app.packageName
                button.isEnabled = true
                if (!waitingForInstaller) letScreenSleep()
                show()
            }
        }
    }

    private companion object {
        const val TAG = "InstallAppsFragment"

        // Long enough for a slow download and an unhurried user, and only a
        // backstop: the lock is released as soon as the installer is done
        const val KEEP_AWAKE_MILLIS = 10 * 60 * 1000L
    }
}
