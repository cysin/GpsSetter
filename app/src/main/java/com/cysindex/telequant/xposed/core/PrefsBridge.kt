package com.cysindex.telequant.xposed.core

import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.spoof.FakeEnvironment
import de.robv.android.xposed.XSharedPreferences

/**
 * The hooked process's view of the module's settings.
 *
 * Hooks like `Location.getLatitude` fire at very high rates, so every read here
 * has to be cheap. One [XSharedPreferences] instance is kept and reloaded only
 * when [XSharedPreferences.hasFileChanged] says the file actually changed; the
 * parsed [FakeEnvironment] is cached alongside it and re-parsed on the same
 * signal rather than per access.
 */
object PrefsBridge {

    const val KEY_STARTED = "start"
    const val KEY_LAT = "latitude"
    const val KEY_LNG = "longitude"
    const val KEY_ACCURACY = "accuracy_settings"
    const val KEY_JITTER_RADIUS = "jitter_radius"
    const val KEY_JITTER_MODE = "jitter_mode"
    const val KEY_GCJ02 = "gcj02_output"
    const val KEY_ENVIRONMENT = "active_environment"
    const val KEY_SPOOF_CELL = "spoof_cell"
    const val KEY_SPOOF_WIFI = "spoof_wifi"
    const val KEY_SPOOF_BLUETOOTH = "spoof_bluetooth"
    const val KEY_SPOOF_TIMEZONE = "spoof_timezone"

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
    }

    private var cachedEnvironmentJson: String? = null
    private var cachedEnvironment: FakeEnvironment? = null

    @Synchronized
    private fun prefs(): XSharedPreferences {
        if (prefs.hasFileChanged()) prefs.reload()
        return prefs
    }

    val isStarted: Boolean get() = prefs().getBoolean(KEY_STARTED, false)

    val anchorLat: Double get() = prefs().getFloat(KEY_LAT, DEFAULT_LAT).toDouble()

    val anchorLng: Double get() = prefs().getFloat(KEY_LNG, DEFAULT_LNG).toDouble()

    val accuracy: Float
        get() = prefs().getString(KEY_ACCURACY, "10")?.toFloatOrNull() ?: 10f

    val jitterRadiusMeters: Double
        get() = prefs().getString(KEY_JITTER_RADIUS, "10")?.toDoubleOrNull() ?: 10.0

    val jitterMode: String get() = prefs().getString(KEY_JITTER_MODE, "STATIONARY") ?: "STATIONARY"

    val gcj02Output: Boolean get() = prefs().getBoolean(KEY_GCJ02, false)

    val spoofCell: Boolean get() = prefs().getBoolean(KEY_SPOOF_CELL, true)
    val spoofWifi: Boolean get() = prefs().getBoolean(KEY_SPOOF_WIFI, true)
    val spoofBluetooth: Boolean get() = prefs().getBoolean(KEY_SPOOF_BLUETOOTH, true)
    val spoofTimeZone: Boolean get() = prefs().getBoolean(KEY_SPOOF_TIMEZONE, false)

    /** The active recorded environment, or null when only a bare point is set. */
    @Synchronized
    fun environment(): FakeEnvironment? {
        val json = prefs().getString(KEY_ENVIRONMENT, null)
        if (json != cachedEnvironmentJson) {
            cachedEnvironmentJson = json
            cachedEnvironment = FakeEnvironment.parse(json)
        }
        return cachedEnvironment
    }

    private const val DEFAULT_LAT = 40.7128f
    private const val DEFAULT_LNG = -74.0060f
}
