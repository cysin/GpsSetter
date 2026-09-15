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
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
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

    val getLat : Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

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



    fun update(start:Boolean, la: Double, ln: Double) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putBoolean(START, start)
            prefEditor.apply()
        }

    }




    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }


}