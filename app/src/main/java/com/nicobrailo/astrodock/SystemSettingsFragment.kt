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
import androidx.fragment.app.Fragment

// What the app needs from the system, one item each: what it is, whether it's
// set up, and a button that opens the system dialog or settings screen to set
// it up. Nothing here can be granted by the app itself.
//
// The Portal has all these screens (checked on the device): the role request
// dialog, the device admin dialog, the screensaver list and the "modify system
// settings" screen.
class SystemSettingsFragment : Fragment() {
    // One requirement: shown when it isn't set up, with the intent that sets it,
    // or, for the one the app can set itself, the action that does it
    private class Item(
        val title: String,
        val description: String,
        val buttonText: String?,
        val done: Boolean,
        val intent: Intent?,
        val action: (() -> Unit)? = null,
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
        refresh()
    }

    private fun refresh() {
        items.removeAllViews()
        for (item in requirements()) show(item)
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

        return listOf(
            Item(
                title = getString(R.string.system_home_title),
                description = getString(R.string.system_home_description),
                buttonText = getString(R.string.system_home_button),
                done = isHome,
                // The role dialog if the device has it, else the home screen settings
                intent = homeRoleIntent(context)
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
                // The action really is spelled with the ACTION_ prefix in its
                // own value, unlike every other one here, so this is not the
                // constant's name left behind by mistake. It resolves to
                // com.android.settings/.Settings$NotificationAccessSettingsActivity
                // and starts it, but on the Portal that screen finishes itself
                // about 40ms after it resumes, with nothing in the log, so the
                // button looks dead there and the description says what to run
                // instead. Measured with astrodock stopped too, so it isn't
                // ours; the screensaver and overlay screens in the same app are
                // fine. Kept because it is the one tap that works elsewhere.
                intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
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
        view.findViewById<TextView>(R.id.status).text =
            getString(if (item.done) R.string.system_granted else R.string.system_missing)
        view.findViewById<TextView>(R.id.description).text = item.description

        val button = view.findViewById<Button>(R.id.action)
        val onClick: (() -> Unit)? =
            item.action ?: item.intent?.let { intent -> { systemDialog.launch(intent) } }
        // Nothing to do when it's already set up, except for screens worth
        // revisiting (the screensaver list can also be used to unset it)
        if (onClick == null || (item.done && item.buttonText == null)) {
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
