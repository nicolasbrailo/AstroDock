package com.nicobrailo.astrodock

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import com.google.android.material.slider.RangeSlider
import java.util.Locale

// The night rule's hours, as one slider with a knob for each end. It stores
// them as the two text settings they used to be (Settings.KEY_NIGHT_*_HOUR),
// so Settings.load() and anything that pushes them over adb are unchanged;
// that is why the preference itself persists nothing.
class NightHoursPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_night_hours
        isPersistent = false
        // Only the slider takes touches; tapping the row does nothing
        isSelectable = false
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    // Always what is stored, so a bind never shows a stale summary
    override fun getSummary(): CharSequence = describe(load())

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val summaryView = holder.findViewById(android.R.id.summary) as TextView
        val slider = holder.findViewById(R.id.night_hours_slider) as RangeSlider
        // A recycled row still has the listeners of the last bind
        slider.clearOnChangeListeners()
        // An empty window would never be night
        slider.setMinSeparationValue(1f)
        val (start, end) = load()
        slider.values = listOf(
            NightHours.position(start).toFloat(),
            NightHours.position(end).toFloat(),
        )
        slider.setLabelFormatter { NightHours.format(NightHours.hour(it.toInt())) }
        slider.addOnChangeListener { s, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val hours = NightHours.hour(s.values[0].toInt()) to NightHours.hour(s.values[1].toInt())
            prefs.edit()
                .putString(Settings.KEY_NIGHT_START_HOUR, hours.first.toString())
                .putString(Settings.KEY_NIGHT_END_HOUR, hours.second.toString())
                .apply()
            // Straight onto the view: notifyChanged() would rebind the row,
            // and with it the slider, in the middle of the drag
            summaryView.text = describe(hours)
        }
    }

    // What the settings hold, or the defaults when they are a window the
    // slider can't show (one that includes noon, left by the old text inputs)
    private fun load(): Pair<Int, Int> {
        fun hour(key: String, default: Int) =
            prefs.getString(key, null)?.trim()?.toIntOrNull()?.takeIf { it in Settings.HOUR_RANGE } ?: default
        val start = hour(Settings.KEY_NIGHT_START_HOUR, Settings.DEFAULT_NIGHT_START_HOUR)
        val end = hour(Settings.KEY_NIGHT_END_HOUR, Settings.DEFAULT_NIGHT_END_HOUR)
        return if (NightHours.fits(start, end)) start to end
        else Settings.DEFAULT_NIGHT_START_HOUR to Settings.DEFAULT_NIGHT_END_HOUR
    }

    private fun describe(hours: Pair<Int, Int>) = context.getString(
        R.string.settings_night_hours_summary,
        NightHours.format(hours.first),
        NightHours.format(hours.second),
    )
}

// The slider's scale, as pure functions so they can be unit tested. A night
// usually wraps past midnight, which a slider from 0 to 23 can't show as one
// stretch between two knobs, so the scale runs from noon to 11:00 the next
// day instead, and the start knob can then never pass the end one. What that
// loses is a window that includes noon, and an empty one.
object NightHours {
    // Hours since midnight of the day the scale starts on. The slider in
    // res/layout/preference_night_hours.xml must have the same range.
    val AXIS = 12..35

    fun position(hour: Int): Int = if (hour < 12) hour + 24 else hour

    fun hour(position: Int): Int = position % 24

    // Whether the slider can show this window
    fun fits(startHour: Int, endHour: Int): Boolean = position(startHour) < position(endHour)

    fun format(hour: Int): String = "%02d:00".format(Locale.ROOT, hour)
}
