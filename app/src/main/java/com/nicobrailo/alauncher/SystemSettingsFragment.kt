package com.nicobrailo.alauncher

import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

// What the app needs from the system, one item each: what it is, whether it's
// set up, and a button that opens the system dialog or settings screen to set
// it up. Nothing here can be granted by the app itself.
//
// The Portal has all these screens (checked on the device): the role request
// dialog, the device admin dialog, the screensaver list and the "modify system
// settings" screen.
class SystemSettingsFragment : Fragment() {
    // One requirement: shown when it isn't set up, with the intent that sets it
    private class Item(
        val title: String,
        val description: String,
        val buttonText: String?,
        val done: Boolean,
        val intent: Intent?,
    )

    private lateinit var items: LinearLayout

    // The role dialog identifies the caller through the activity result, so
    // these have to be started for a result, not just started. The result
    // itself is ignored: onResume() re-reads the real state.
    private val systemDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_system_settings, container, false)
        items = view.findViewById(R.id.items)
        return view
    }

    // Rebuilt every time, so coming back from a system dialog shows the result
    override fun onResume() {
        super.onResume()
        items.removeAllViews()
        for (item in requirements()) show(item)
    }

    private fun requirements(): List<Item> {
        val context = requireContext()
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val roleManager = context.getSystemService(RoleManager::class.java)

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val isHome = context.packageManager.resolveActivity(home, 0)
            ?.activityInfo?.packageName == context.packageName
        val screensavers = AndroidSettings.Secure
            .getString(context.contentResolver, "screensaver_components")
        val sleepTimeout = AndroidSettings.Secure.getInt(context.contentResolver, "sleep_timeout", -1)

        return listOf(
            Item(
                title = getString(R.string.system_home_title),
                description = getString(R.string.system_home_description),
                buttonText = getString(R.string.system_home_button),
                done = isHome,
                // The role dialog if the device has it, else the home screen settings
                intent = roleManager?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                    ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    ?: Intent(AndroidSettings.ACTION_HOME_SETTINGS),
            ),
            Item(
                title = getString(R.string.system_dream_title),
                description = getString(R.string.system_dream_description),
                buttonText = getString(R.string.system_dream_button),
                done = screensavers?.contains(context.packageName) == true,
                intent = Intent(AndroidSettings.ACTION_DREAM_SETTINGS),
            ),
            Item(
                title = getString(R.string.system_admin_title),
                description = getString(R.string.system_admin_description),
                buttonText = getString(R.string.system_admin_button),
                done = dpm?.isAdminActive(ScreenAdminReceiver.component(context)) == true,
                intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ScreenAdminReceiver.component(context)
                    )
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.system_admin_explanation)
                    ),
            ),
            Item(
                title = getString(R.string.system_write_settings_title),
                description = getString(R.string.system_write_settings_description),
                buttonText = getString(R.string.system_write_settings_button),
                done = AndroidSettings.System.canWrite(context),
                intent = Intent(
                    AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ),
            ),
            Item(
                title = getString(R.string.system_overlay_title),
                description = getString(R.string.system_overlay_description),
                buttonText = getString(R.string.system_overlay_button),
                done = AndroidSettings.canDrawOverlays(context),
                intent = Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ),
            ),
            Item(
                title = getString(R.string.system_media_title),
                description = getString(R.string.system_media_description),
                buttonText = getString(R.string.system_media_button),
                done = AndroidSettings.Secure
                    .getString(context.contentResolver, "enabled_notification_listeners")
                    ?.contains(context.packageName) == true,
                intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            ),
            Item(
                title = getString(R.string.system_install_title),
                description = getString(R.string.system_install_description),
                buttonText = getString(R.string.system_install_button),
                done = context.packageManager.canRequestPackageInstalls(),
                intent = Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ),
            ),
            // Only adb can grant this one, so there's nothing to tap: the
            // command is in the description, and tools/setup-device.sh runs it
            Item(
                title = getString(R.string.system_sleep_timeout_title, formatMillis(sleepTimeout)),
                description = getString(R.string.system_sleep_timeout_description),
                buttonText = null,
                done = ScreenControl.canWriteSecureSettings(context),
                intent = null,
            ),
        )
    }

    private fun show(item: Item) {
        val view = layoutInflater.inflate(R.layout.item_system_setting, items, false)
        view.findViewById<TextView>(R.id.title).text = item.title
        view.findViewById<TextView>(R.id.status).text =
            getString(if (item.done) R.string.system_granted else R.string.system_missing)
        view.findViewById<TextView>(R.id.description).text = item.description

        val button = view.findViewById<Button>(R.id.action)
        // Nothing to do when it's already set up, except for screens worth
        // revisiting (the screensaver list can also be used to unset it)
        if (item.intent == null || (item.done && item.buttonText == null)) {
            button.visibility = View.GONE
        } else {
            button.text = item.buttonText
            button.setOnClickListener { systemDialog.launch(item.intent) }
        }
        items.addView(view)
    }

    private fun formatMillis(ms: Int): String = when {
        ms < 0 -> "?"
        ms >= 60_000 -> "${ms / 60_000} min"
        else -> "${ms / 1000} s"
    }
}
