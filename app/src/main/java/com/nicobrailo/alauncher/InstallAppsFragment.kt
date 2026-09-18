package com.nicobrailo.alauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.nicobrailo.alauncher.apps.ApkInstaller
import com.nicobrailo.alauncher.apps.INSTALLABLE_APPS
import com.nicobrailo.alauncher.apps.Installable
import kotlinx.coroutines.launch
import java.io.IOException

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_system_settings, container, false)
        items = view.findViewById(R.id.items)
        installer = ApkInstaller(requireContext())
        return view
    }

    // Rebuilt every time, so an app installed meanwhile shows as installed
    override fun onResume() {
        super.onResume()
        if (downloading.isEmpty()) show()
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
        // The download is no use once the app is there
        if (installed) installer.forget(app)
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
        return view
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = installer.download(app) { percent ->
                    status.text = if (percent < 0) {
                        getString(R.string.install_downloading_unknown)
                    } else {
                        getString(R.string.install_downloading, percent)
                    }
                }
                // The system takes over from here and asks the user to confirm
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
                show()
            }
        }
    }

    private companion object {
        const val TAG = "InstallAppsFragment"
    }
}
