package com.cysindex.telequant.xposed

import com.cysindex.telequant.BuildConfig
import de.robv.android.xposed.XSharedPreferences

/**
 * Reads the module's world-readable preferences from inside a hooked process.
 *
 * The previous implementation built a fresh [XSharedPreferences] on *every*
 * property access, so a single `getLatitude()` hook did a file read — and hooks
 * like [LocationHook]'s `getLatitude` fire constantly. It also exposed
 * `val reload = pref().reload()`, which evaluated once at construction and was
 * therefore never a usable refresh.
 *
 * Instead keep one instance and let [XSharedPreferences.hasFileChanged] decide
 * when a reload is actually warranted.
 */
class Xshare {

    private val pref: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs").also {
            it.makeWorldReadable()
        }
    }

    /** Reloads only when the backing file changed since the last read. */
    private fun prefs(): XSharedPreferences {
        if (pref.hasFileChanged()) pref.reload()
        return pref
    }

    val isStarted: Boolean
        get() = prefs().getBoolean("start", false)

    val getLat: Double
        get() = prefs().getFloat("latitude", DEFAULT_LAT).toDouble()

    val getLng: Double
        get() = prefs().getFloat("longitude", DEFAULT_LNG).toDouble()

    val isRandomPosition: Boolean
        get() = prefs().getBoolean("random_position", false)

    val accuracy: String?
        get() = prefs().getString("accuracy_settings", "10")

    private companion object {
        // Kept in sync with PrefManager's defaults; the two used to disagree, and
        // LocationHook carried a third pair with the longitude sign dropped.
        const val DEFAULT_LAT = 40.7128f
        const val DEFAULT_LNG = -74.0060f
    }
}
