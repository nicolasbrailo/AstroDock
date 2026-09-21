package com.nicobrailo.astrodock

import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

// What the app needs from the system. At the top, a list of what is missing
// and what doesn't work without it, with a pointer to tools/setup-device.sh,
// which grants all of it over adb. Below, one item each: what it is, whether
// it's set up, and, while it isn't, a button that opens the system dialog or
// settings screen to set it up, where that screen works.
//
// The Portal has the role request dialog, the screensaver list and the
// "modify system settings" screen, but its device admin dialog refuses the app
// and its notification access screen closes itself, so those two only have
// the script there.
class SystemSettingsFragment : Fragment() {
    // One requirement, with the intent that sets it, or, for the one the app
    // can set itself, the action that does it. `missing` is its line in the
    // list at the top, null for a preference rather than a permission.
    // `adbOnly` hides the button where the system screen can't grant it.
    private class Item(
        val title: String,
        val description: String,
        val buttonText: String?,
        val done: Boolean,
        val intent: Intent?,
        val action: (() -> Unit)? = null,
        val missing: String? = null,
        val adbOnly: Boolean = false,
    )

    private lateinit var items: LinearLayout
    private lateinit var summary: TextView
    private lateinit var setupNote: TextView

    // The role dialog identifies the caller through the activity result, so
    // these have to be started for a result, not just started. The result
    // itself is ignored: onResume() re-reads the real state.
    private val systemDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_system_settings, container, false)
        items = view.findViewById(R.id.items)
        summary = view.findViewById(R.id.summary)
        setupNote = view.findViewById(R.id.setup_note)
        return view
    }

    // Rebuilt every time, so coming back from a system dialog shows the result
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val requirements = requirements()
        val missing = requirements.filter { !it.done }.mapNotNull { it.missing }
        if (missing.isEmpty()) {
            summary.text = getString(R.string.system_all_done)
            summary.setTextColor(ContextCompat.getColor(requireContext(), R.color.ok_text))
            setupNote.visibility = View.GONE
        } else {
            summary.text = missing.joinToString("\n") { "• $it" }
            summary.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_text))
            setupNote.visibility = View.VISIBLE
        }
        items.removeAllViews()
        for (item in requirements) show(item)
    }

    private fun requirements(): List<Item> {
        val context = requireContext()
        val dpm = context.getSystemService(DevicePolicyManager::class.java)

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val isHome = context.packageManager.resolveActivity(home, 0)
            ?.activityInfo?.packageName == context.packageName
        val screensavers = AndroidSettings.Secure
            .getString(context.contentResolver, "screensaver_components")
        val sleepTimeout = AndroidSettings.Secure.getInt(context.contentResolver, "sleep_timeout", -1)
        val contrastOn = TextContrast.isEnabled(context)
        // Its device admin and notification access screens can't grant
        // anything (see the class comment)
        val isPortal = Build.MANUFACTURER.equals("Facebook", ignoreCase = true)

        return listOf(
            Item(
                title = getString(R.string.system_home_title),
                description = getString(R.string.system_home_description),
                buttonText = getString(R.string.system_home_button),
                done = isHome,
                // The role dialog if the device has it, else the home screen settings
                intent = homeRoleIntent(context)
                    ?: Intent(AndroidSettings.ACTION_HOME_SETTINGS),
                missing = getString(R.string.system_home_missing),
            ),
            Item(
                title = getString(R.string.system_dream_title),
                description = getString(R.string.system_dream_description),
                buttonText = getString(R.string.system_dream_button),
                done = screensavers?.contains(context.packageName) == true,
                intent = Intent(AndroidSettings.ACTION_DREAM_SETTINGS),
                missing = getString(R.string.system_dream_missing),
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
                missing = getString(R.string.system_admin_missing),
                adbOnly = isPortal,
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
                missing = getString(R.string.system_write_settings_missing),
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
                missing = getString(R.string.system_overlay_missing),
            ),
            Item(
                title = getString(R.string.system_media_title),
                description = getString(R.string.system_media_description),
                buttonText = getString(R.string.system_media_button),
                done = AndroidSettings.Secure
                    .getString(context.contentResolver, "enabled_notification_listeners")
                    ?.contains(context.packageName) == true,
                // The action really is spelled with the ACTION_ prefix in its
                // own value, unlike every other one here, so this is not the
                // constant's name left behind by mistake. It resolves to
                // com.android.settings/.Settings$NotificationAccessSettingsActivity
                // and starts it, but on the Portal that screen finishes itself
                // about 40ms after it resumes, with nothing in the log, so the
                // button is hidden there. Measured with astrodock stopped too,
                // so it isn't ours; the screensaver and overlay screens in the
                // same app are fine. Kept because it is the one tap that works
                // elsewhere.
                intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                missing = getString(R.string.system_media_missing),
                adbOnly = isPortal,
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
                missing = getString(R.string.system_install_missing),
            ),
            // Only adb can grant this one, so there's nothing to tap: the
            // command is in the description, and tools/setup-device.sh runs it
            Item(
                title = getString(R.string.system_sleep_timeout_title, formatMillis(sleepTimeout)),
                description = getString(R.string.system_sleep_timeout_description),
                buttonText = null,
                done = ScreenControl.canWriteSecureSettings(context),
                intent = null,
                missing = getString(R.string.system_sleep_timeout_missing),
                adbOnly = true,
            ),
            // The only one the app switches itself, so it has no system screen
            // to open. It rides on the same grant as the delay above, and
            // without it there is nothing to tap.
            Item(
                title = getString(R.string.system_contrast_title),
                description = getString(R.string.system_contrast_description),
                buttonText = getString(
                    if (contrastOn) R.string.system_contrast_button_off
                    else R.string.system_contrast_button_on
                ),
                done = contrastOn,
                intent = null,
                action = if (ScreenControl.canWriteSecureSettings(context)) {
                    {
                        TextContrast.setEnabled(context, !contrastOn)
                        refresh()
                    }
                } else {
                    null
                },
            ),
        )
    }

    // The dialog that asks to become the home screen, which is the shortest
    // way there. RoleManager is API 29, so on anything older the caller falls
    // back to the home screen settings, where the user picks it themselves.
    private fun homeRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    private fun show(item: Item) {
        val view = layoutInflater.inflate(R.layout.item_system_setting, items, false)
        view.findViewById<TextView>(R.id.title).text = item.title
        view.findViewById<TextView>(R.id.status).apply {
            text = getString(if (item.done) R.string.system_granted else R.string.system_missing)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (item.done) R.color.ok_text else R.color.error_text
                )
            )
        }
        view.findViewById<TextView>(R.id.description).text = item.description

        val button = view.findViewById<Button>(R.id.action)
        // A setting the app switches itself always has its button, since it
        // goes both ways. A permission only has one while it's missing, and
        // only where the system screen can actually grant it.
        val onClick: (() -> Unit)? = item.action
            ?: item.intent
                ?.takeIf { !item.done && !item.adbOnly }
                ?.let { intent -> { systemDialog.launch(intent) } }
        if (onClick == null) {
            button.visibility = View.GONE
        } else {
            button.text = item.buttonText
            button.setOnClickListener { onClick() }
        }
        items.addView(view)
    }

    private fun formatMillis(ms: Int): String = when {
        ms < 0 -> "?"
        ms >= 60_000 -> "${ms / 60_000} min"
        else -> "${ms / 1000} s"
    }
}
