package com.nicobrailo.astrodock

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nicobrailo.astrodock.call.CallPeer
import com.nicobrailo.astrodock.call.CallRouter
import com.nicobrailo.astrodock.call.CallSettings
import com.nicobrailo.astrodock.mqtt.StateReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Calls between Portals (CALLING.md), as an app of their own in the app list,
// like the alarms: whether this device takes part and who may call it, then
// the Portals it can call. Runs in the main process, which holds the MQTT
// connection that knows the other devices; the call itself is CallActivity,
// in a process of its own.
class CallsActivity : AppCompatActivity() {
    private lateinit var enabled: SwitchMaterial
    private lateinit var permission: View
    private lateinit var allowed: TextView
    private lateinit var peersNote: TextView
    private lateinit var peersList: LinearLayout
    // What is listed, so the list is only rebuilt when it changes
    private var shown: List<CallPeer>? = null

    private val permissionDialog =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calls)
        title = getString(R.string.calls_title)
        enabled = findViewById(R.id.enabled)
        permission = findViewById(R.id.permission)
        allowed = findViewById(R.id.allowed)
        peersNote = findViewById(R.id.peers_note)
        peersList = findViewById(R.id.peers)

        enabled.setOnCheckedChangeListener { _, on ->
            if (on == CallSettings.load(this).enabled) return@setOnCheckedChangeListener
            prefs().edit().putBoolean(CallSettings.KEY_ENABLED, on).apply()
            // Says so in this device's availability record now, rather than
            // when a slideshow next appears
            StateReporter.get(this).applySettings()
            if (on && !CallSettings.canCapture(this)) permissionDialog.launch(CallSettings.PERMISSIONS)
            refresh()
        }
        findViewById<Button>(R.id.allow).setOnClickListener { permissionDialog.launch(CallSettings.PERMISSIONS) }
        findViewById<View>(R.id.allowed_row).setOnClickListener { editAllowed() }

        // Other devices come and go on the broker with nothing to say so, so
        // the list is looked at again every few seconds while it's on screen
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    showPeers()
                    delay(PEERS_REFRESH_MILLIS)
                }
            }
        }
    }

    // Also picks up what was changed elsewhere meanwhile (push-config.sh, or
    // the permission granted in the System tab)
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val settings = CallSettings.load(this)
        enabled.isChecked = settings.enabled
        permission.visibility =
            if (settings.enabled && !CallSettings.canCapture(this)) View.VISIBLE else View.GONE
        allowed.text = prefs().getString(CallSettings.KEY_ALLOWED, null)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.calls_allowed_anyone)
        shown = null
        showPeers()
    }

    // The devices whose availability record says they take calls
    private fun showPeers() {
        val reporter = StateReporter.get(this)
        val peers = if (CallSettings.load(this).enabled) reporter.callablePeers() else emptyList()
        if (peers == shown) return
        shown = peers
        peersNote.text = when {
            !CallSettings.load(this).enabled -> getString(R.string.calls_off_note)
            reporter.ownPrefix == null -> getString(R.string.call_no_broker)
            peers.isEmpty() -> getString(R.string.call_no_peers)
            else -> ""
        }
        peersNote.visibility = if (peersNote.text.isEmpty()) View.GONE else View.VISIBLE
        peersList.removeAllViews()
        for (peer in peers) {
            peersList.addView(
                MaterialButton(this).apply {
                    text = peer.name
                    setIconResource(R.drawable.ic_call)
                    textSize = 20f
                    setOnClickListener { call(peer) }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (8 * resources.displayMetrics.density).toInt() },
            )
        }
    }

    private fun call(peer: CallPeer) {
        val router = CallRouter.get(this)
        if (router.active) {
            Toast.makeText(this, R.string.call_busy, Toast.LENGTH_SHORT).show()
            return
        }
        // The call has a task of its own, so this screen goes, and hanging up
        // returns to the slideshow
        if (router.placeCall(peer)) finish()
    }

    private fun editAllowed() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs().getString(CallSettings.KEY_ALLOWED, null).orEmpty())
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calls_allowed)
            .setMessage(R.string.calls_allowed_hint)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs().edit().putString(CallSettings.KEY_ALLOWED, input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Where CallSettings reads them, as the MQTT tab used to edit them
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private companion object {
        const val PEERS_REFRESH_MILLIS = 2_000L
    }
}
