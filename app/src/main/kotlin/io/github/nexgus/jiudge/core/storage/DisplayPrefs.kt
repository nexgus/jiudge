package io.github.nexgus.jiudge.core.storage

import android.content.Context
import io.github.nexgus.jiudge.data.route.PathKind

/**
 * Display choices that carry over to the next launch: the on/off state of the two switches on the
 * map that apply to every path shown there (slope colouring and distance markers), and which tab the
 * file manager was last left on. They live in a private [android.content.SharedPreferences] file.
 *
 * Reads are meant to happen once, when the map screen is set up. Writes use `apply()`: they return
 * at once and reach the disk in the background.
 */
class DisplayPrefs(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether paths are coloured by slope. Off until the user turns it on. */
    var showSlope: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SLOPE, DEFAULT_SHOW_SLOPE)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SLOPE, value).apply()

    /** Whether paths carry their kilometre labels and total-distance label. On until the user turns it off. */
    var showDistanceMarkers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DISTANCE_MARKERS, DEFAULT_SHOW_DISTANCE_MARKERS)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DISTANCE_MARKERS, value).apply()

    /**
     * The tab the file manager shows. A missing or unrecognised stored value reads as
     * [DEFAULT_MANAGER_KIND].
     */
    var managerKind: PathKind
        get() {
            val stored = prefs.getString(KEY_MANAGER_KIND, null)
            return PathKind.entries.firstOrNull { it.name == stored } ?: DEFAULT_MANAGER_KIND
        }
        set(value) = prefs.edit().putString(KEY_MANAGER_KIND, value.name).apply()

    companion object {
        const val FILE_NAME = "display_prefs"
        const val KEY_SHOW_SLOPE = "show_slope"
        const val KEY_SHOW_DISTANCE_MARKERS = "show_distance_markers"
        const val KEY_MANAGER_KIND = "manager_kind"
        const val DEFAULT_SHOW_SLOPE = false
        const val DEFAULT_SHOW_DISTANCE_MARKERS = true
        val DEFAULT_MANAGER_KIND = PathKind.TRACK
    }
}
