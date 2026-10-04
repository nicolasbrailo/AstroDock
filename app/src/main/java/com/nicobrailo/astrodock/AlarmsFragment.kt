package com.nicobrailo.astrodock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nicobrailo.astrodock.alarm.Alarm
import com.nicobrailo.astrodock.alarm.AlarmReceiver
import com.nicobrailo.astrodock.alarm.AlarmRinger
import com.nicobrailo.astrodock.alarm.AlarmSchedule
import com.nicobrailo.astrodock.alarm.AlarmStore
import com.nicobrailo.astrodock.alarm.MediaStarter
import com.nicobrailo.astrodock.alarm.RecentPlaylists
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

// The Alarms app (AlarmsActivity): the alarms (alarm/Alarm.kt), each with a switch, and an
// editor for one. Every change is saved at once and the next alarm scheduled
// again (AlarmReceiver.schedule).
class AlarmsFragment : Fragment() {
    private lateinit var store: AlarmStore
    private lateinit var items: LinearLayout
    private lateinit var next: TextView
    private lateinit var noMediaAccess: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_alarms, container, false)
        store = AlarmStore(requireContext())
        items = view.findViewById(R.id.items)
        next = view.findViewById(R.id.next)
        noMediaAccess = view.findViewById(R.id.no_media_access)
        view.findViewById<Button>(R.id.add).setOnClickListener { edit(null) }
        return view
    }

    // Rebuilt every time, since notification access may have been granted in
    // the System tab meanwhile
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val context = requireContext()
        noMediaAccess.visibility = if (MediaStarter.canReadSessions(context)) View.GONE else View.VISIBLE
        val alarms = store.load()
        val soonest = AlarmSchedule.soonest(alarms, System.currentTimeMillis(), ZoneId.systemDefault())
        next.text = if (soonest == null) {
            getString(R.string.alarms_next_none)
        } else {
            val at = Instant.ofEpochMilli(soonest.second).atZone(ZoneId.systemDefault())
            val day = at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            getString(R.string.alarms_next, "$day ${soonest.first.time}")
        }

        items.removeAllViews()
        if (alarms.isEmpty()) {
            items.addView(TextView(context).apply {
                text = getString(R.string.alarms_none)
                textSize = 16f
            })
        }
        for (alarm in alarms.sortedWith(compareBy({ it.hour }, { it.minute }))) {
            val row = layoutInflater.inflate(R.layout.item_alarm, items, false)
            row.findViewById<TextView>(R.id.time).text = alarm.time
            row.findViewById<TextView>(R.id.summary).text = summary(alarm)
            row.findViewById<SwitchMaterial>(R.id.enabled).apply {
                isChecked = alarm.enabled
                setOnCheckedChangeListener { _, on -> save(alarm.copy(enabled = on)) }
            }
            row.setOnClickListener { edit(alarm) }
            items.addView(row)
        }
    }

    // "Weekdays · Spotify · Deep Focus · shuffled"
    private fun summary(alarm: Alarm): String {
        val days = when (AlarmSchedule.kind(alarm.days)) {
            AlarmSchedule.Days.ONCE -> getString(R.string.alarm_days_once)
            AlarmSchedule.Days.EVERY_DAY -> getString(R.string.alarm_days_every_day)
            AlarmSchedule.Days.WEEKDAYS -> getString(R.string.alarm_days_weekdays)
            AlarmSchedule.Days.WEEKENDS -> getString(R.string.alarm_days_weekends)
            AlarmSchedule.Days.SOME -> alarm.days.sorted().joinToString(", ") { dayName(it) }
        }
        val app = alarm.app ?: return "$days · ${getString(R.string.alarm_sound_only)}"
        val parts = mutableListOf(days, MediaStarter.label(requireContext(), app))
        parts += alarm.playTitle.ifEmpty { alarm.play }.ifEmpty { getString(R.string.alarm_what_resume) }
        if (alarm.shuffle) parts += getString(R.string.alarm_shuffled)
        return parts.joinToString(" · ")
    }

    private fun save(alarm: Alarm) {
        val alarms = store.load()
        store.save(if (alarms.any { it.id == alarm.id }) {
            alarms.map { if (it.id == alarm.id) alarm else it }
        } else {
            alarms + alarm
        })
        AlarmReceiver.schedule(requireContext())
        refresh()
    }

    private fun delete(alarm: Alarm) {
        store.save(store.load().filter { it.id != alarm.id })
        AlarmReceiver.schedule(requireContext())
        refresh()
    }

    // The editor, for a new alarm when `existing` is null
    private fun edit(existing: Alarm?) {
        val context = requireContext()
        val alarm = existing ?: Alarm(id = UUID.randomUUID().toString())
        val view = layoutInflater.inflate(R.layout.dialog_alarm, null)

        val time = view.findViewById<TimePicker>(R.id.time).apply {
            setIs24HourView(true)
            hour = alarm.hour
            minute = alarm.minute
        }

        val days = view.findViewById<MaterialButtonToggleGroup>(R.id.days)
        val dayButtons = DayOfWeek.entries.associateWith { day ->
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = dayName(day)
                minWidth = 0
                minimumWidth = 0
                setPadding(0, paddingTop, 0, paddingBottom)
                days.addView(this, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        for (day in alarm.days) dayButtons[day]?.let { days.check(it.id) }
        // Selects every day, or clears them all once they are, saying which
        val allDays = view.findViewById<Button>(R.id.days_all)
        fun showAllDays() {
            val all = days.checkedButtonIds.size == dayButtons.size
            allDays.setText(if (all) R.string.alarm_days_clear else R.string.alarm_days_all)
        }
        allDays.setOnClickListener {
            if (days.checkedButtonIds.size == dayButtons.size) {
                days.clearChecked()
            } else {
                for (button in dayButtons.values) days.check(button.id)
            }
        }
        days.addOnButtonCheckedListener { _, _, _ -> showAllDays() }
        showAllDays()

        // The apps that can be started, plus the one already chosen if it has
        // since been uninstalled, so saving doesn't quietly change it
        val apps = mutableListOf<Pair<String?, String>>(null to getString(R.string.alarm_app_none))
        val candidates = MediaStarter.candidates(context)
        apps += candidates
        if (alarm.app != null && candidates.none { it.first == alarm.app }) {
            apps += alarm.app to getString(R.string.alarm_app_missing, alarm.app)
        }
        val app = view.findViewById<Spinner>(R.id.app).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, apps.map { it.second })
            // A new alarm starts with the first app rather than none, since
            // starting music is what the alarms are for
            val chosen = if (existing == null) apps.getOrNull(1)?.first else alarm.app
            setSelection(apps.indexOfFirst { it.first == chosen }.coerceAtLeast(0))
        }

        // What to play: what the app played last, then the playlists it was
        // seen playing (RecentPlaylists), most recent first, the one playing
        // now marked. A new alarm starts with that one, since playing it and
        // then setting the alarm is the easy way to pick it.
        val playlists = RecentPlaylists(context)
        val playSection = view.findViewById<View>(R.id.play_section)
        val play = view.findViewById<Spinner>(R.id.play)
        var choices = emptyList<PlayChoice>()
        var choicesFor: String? = null
        fun showChoices(packageName: String?) {
            if (choicesFor == packageName && choices.isNotEmpty()) return
            choicesFor = packageName
            playSection.visibility = if (packageName == null) View.GONE else View.VISIBLE
            if (packageName == null) {
                choices = emptyList()
                return
            }
            val now = playlists.nowPlaying(packageName)
            val list = mutableListOf(PlayChoice("", "", getString(R.string.alarm_play_resume)))
            for (p in playlists.forApp(packageName)) {
                list += PlayChoice(p.uri, p.title, if (p == now) getString(R.string.alarm_play_playing_now, p.title) else p.title)
            }
            // Chosen before and no longer listed (or typed, in the version
            // that took a link): kept, so saving doesn't quietly change it
            val ours = existing != null && packageName == alarm.app
            if (ours && alarm.play.isNotEmpty() && list.none { it.uri == alarm.play }) {
                list += PlayChoice(alarm.play, alarm.playTitle, alarm.playTitle.ifEmpty { alarm.play })
            }
            choices = list
            play.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, list.map { it.label })
            val wanted = if (ours) alarm.play else now?.uri.orEmpty()
            play.setSelection(list.indexOfFirst { it.uri == wanted }.coerceAtLeast(0))
        }
        showChoices(apps[app.selectedItemPosition].first)
        app.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, item: View?, position: Int, id: Long) =
                showChoices(apps[position].first)
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        val shuffle = view.findViewById<CheckBox>(R.id.shuffle).apply { isChecked = alarm.shuffle }
        val volumeLabel = view.findViewById<TextView>(R.id.volume_label)
        val volume = view.findViewById<SeekBar>(R.id.volume).apply {
            progress = alarm.volume
            volumeLabel.text = getString(R.string.alarm_volume, progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    volumeLabel.text = getString(R.string.alarm_volume, value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }

        fun edited() = alarm.copy(
            enabled = existing?.enabled ?: true,
            hour = time.hour,
            minute = time.minute,
            days = dayButtons.filterValues { days.checkedButtonIds.contains(it.id) }.keys,
            app = apps[app.selectedItemPosition].first,
            play = choices.getOrNull(play.selectedItemPosition)?.uri.orEmpty(),
            playTitle = choices.getOrNull(play.selectedItemPosition)?.title.orEmpty(),
            shuffle = shuffle.isChecked,
            volume = volume.progress,
        )

        // Saved first, like Save does, so what is tested is what will ring:
        // opening a playlist goes back to the home screen, which can close
        // this screen and the edits with it
        view.findViewById<Button>(R.id.test).setOnClickListener {
            val tested = edited().copy(enabled = true)
            save(tested)
            AlarmRinger.ring(context, tested)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.alarm_dialog_new else R.string.alarm_dialog_edit)
            .setView(view)
            // An edit switches the alarm back on: whoever changed it wants it
            .setPositiveButton(R.string.alarm_save) { _, _ -> save(edited().copy(enabled = true)) }
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (existing != null) setNeutralButton(R.string.alarm_delete) { _, _ -> delete(existing) } }
            // "Test" may have left the alarm sound playing, and the slideshow
            // that a touch would stop it from isn't on screen
            .setOnDismissListener { AlarmRinger.silence() }
            .create()
        dialog.show()
    }

    // One entry of "what to play": the URI the alarm stores (empty for what
    // played last), its name, and how the list shows it
    private data class PlayChoice(val uri: String, val title: String, val label: String)

    private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
