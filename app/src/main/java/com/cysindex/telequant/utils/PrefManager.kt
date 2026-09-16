package com.cysindex.telequant.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import rikka.material.app.DayNightDelegate


@SuppressLint("WorldReadableFiles")
object PrefManager   {

    // These keys are the contract with the hooked process; PrefsBridge reads
    // the same file through XSharedPreferences, so the two must stay in step.
    private const val START = "start"
    // Double-bit keys; the *_FLOAT names are what older builds wrote.
    private const val LATITUDE = "latitude_d"
    private const val LONGITUDE = "longitude_d"
    private const val LATITUDE_FLOAT = "latitude"
    private const val LONGITUDE_FLOAT = "longitude"
    private const val ACCURACY_SETTING = "accuracy_settings"
    private const val DARK_THEME = "dark_theme"
    private const val JITTER_RADIUS = "jitter_radius"
    private const val JITTER_MODE = "jitter_mode"
    private const val GCJ02_OUTPUT = "gcj02_output"
    private const val SPOOF_CELL = "spoof_cell"
    private const val SPOOF_WIFI = "spoof_wifi"
    private const val SPOOF_BLUETOOTH = "spoof_bluetooth"
    private const val SPOOF_TIMEZONE = "spoof_timezone"
    private const val ACTIVE_ENVIRONMENT = "active_environment"
    private const val TILE_PROXY_ENABLED = "tile_proxy_enabled"
    private const val TILE_PROXY_HOST = "tile_proxy_host"
    private const val TILE_PROXY_PORT = "tile_proxy_port"
    private const val OFFLINE_MAP = "offline_map"
    private const val JOYSTICK_SPEED = "joystick_speed"


    private val pref: SharedPreferences by lazy {
        try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_WORLD_READABLE
            )
        }catch (e:SecurityException){
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_PRIVATE
            )
        }

    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    /**
     * Coordinates are stored as the raw bits of a Double.
     *
     * They used to be Floats, which carry about seven significant digits — at
     * longitude 100 that leaves roughly a metre of resolution. The joystick
     * moves in sub-metre steps and the jitter walk in smaller ones still, so
     * individual steps were being swallowed by rounding. SharedPreferences has
     * no putDouble, hence the bit round-trip.
     */
    val getLat: Double
        get() = readCoordinate(LATITUDE, LATITUDE_FLOAT, DEFAULT_LAT)

    val getLng: Double
        get() = readCoordinate(LONGITUDE, LONGITUDE_FLOAT, DEFAULT_LNG)

    private fun readCoordinate(key: String, legacyKey: String, fallback: Double): Double {
        if (pref.contains(key)) {
            return Double.fromBits(pref.getLong(key, fallback.toRawBits()))
        }
        // Values written by an older build.
        if (pref.contains(legacyKey)) {
            return pref.getFloat(legacyKey, fallback.toFloat()).toDouble()
        }
        return fallback
    }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, DayNightDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isJoyStickEnable: Boolean
    get() = pref.getBoolean("isJoyStickEnable",false)
    set(value) = pref.edit().putBoolean("isJoyStickEnable",value).apply()

    /**
     * Wander radius in metres; 0 pins the position exactly. Replaces the old
     * boolean "random position", which the jitter engine no longer reads.
     */
    var jitterRadius: String?
        get() = pref.getString(JITTER_RADIUS, "10")
        set(value) { pref.edit().putString(JITTER_RADIUS, value).apply() }

    /** One of JitterEngine.Mode: STATIONARY, WALKING, DRIVING. */
    var jitterMode: String?
        get() = pref.getString(JITTER_MODE, "STATIONARY")
        set(value) { pref.edit().putString(JITTER_MODE, value).apply() }

    /** Emit GCJ-02 instead of WGS-84. Only affects coordinates inside China. */
    var gcj02Output: Boolean
        get() = pref.getBoolean(GCJ02_OUTPUT, false)
        set(value) = pref.edit().putBoolean(GCJ02_OUTPUT, value).apply()

    var spoofCell: Boolean
        get() = pref.getBoolean(SPOOF_CELL, true)
        set(value) = pref.edit().putBoolean(SPOOF_CELL, value).apply()

    var spoofWifi: Boolean
        get() = pref.getBoolean(SPOOF_WIFI, true)
        set(value) = pref.edit().putBoolean(SPOOF_WIFI, value).apply()

    var spoofBluetooth: Boolean
        get() = pref.getBoolean(SPOOF_BLUETOOTH, true)
        set(value) = pref.edit().putBoolean(SPOOF_BLUETOOTH, value).apply()

    var spoofTimeZone: Boolean
        get() = pref.getBoolean(SPOOF_TIMEZONE, false)
        set(value) = pref.edit().putBoolean(SPOOF_TIMEZONE, value).apply()

    /** The active recorded environment, serialised as JSON. */
    var activeEnvironment: String?
        get() = pref.getString(ACTIVE_ENVIRONMENT, null)
        set(value) { pref.edit().putString(ACTIVE_ENVIRONMENT, value).apply() }

    /** Route tile downloads through a local HTTP (CONNECT) proxy. */
    var tileProxyEnabled: Boolean
        get() = pref.getBoolean(TILE_PROXY_ENABLED, true)
        set(value) = pref.edit().putBoolean(TILE_PROXY_ENABLED, value).apply()

    var tileProxyHost: String?
        get() = pref.getString(TILE_PROXY_HOST, "127.0.0.1")
        set(value) { pref.edit().putString(TILE_PROXY_HOST, value).apply() }

    var tileProxyPort: String?
        get() = pref.getString(TILE_PROXY_PORT, "33009")
        set(value) { pref.edit().putString(TILE_PROXY_PORT, value).apply() }

    /** Joystick travel speed in metres per second at full deflection. */
    var joystickSpeed: String?
        get() = pref.getString(JOYSTICK_SPEED, "8")
        set(value) { pref.edit().putString(JOYSTICK_SPEED, value).apply() }

    /** Serve tiles from the cache only; nothing is fetched. */
    var offlineMap: Boolean
        get() = pref.getBoolean(OFFLINE_MAP, false)
        set(value) = pref.edit().putBoolean(OFFLINE_MAP, value).apply()



    fun update(start: Boolean, la: Double, ln: Double) {
        runInBackground {
            pref.edit()
                .putLong(LATITUDE, la.toRawBits())
                .putLong(LONGITUDE, ln.toRawBits())
                .putBoolean(START, start)
                .apply()
        }
    }

    private const val DEFAULT_LAT = 40.7128
    private const val DEFAULT_LNG = -74.0060




    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }


}