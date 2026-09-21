package com.cysindex.telequant.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.config.ConfigContract
import com.cysindex.telequant.config.ConfigKeys
import com.cysindex.telequant.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.material.app.DayNightDelegate


@SuppressLint("WorldReadableFiles")
object PrefManager   {

    // The keys the hooked process reads are the contract between the two
    // sides and live in ConfigKeys; what is listed here is this App's own.
    private const val DARK_THEME = "dark_theme"
    private const val ROOTLESS_MODE = "rootless_mode"
    private const val HOOK_CHECK_INS = "hook_check_ins"
    private const val PROXY_TILES = "proxy_tiles_enabled"
    private const val GEOCODER_PROXY_ENABLED = "geocoder_proxy_enabled"
    private const val TILE_PROXY_HOST = "tile_proxy_host"
    private const val TILE_PROXY_PORT = "tile_proxy_port"
    private const val OFFLINE_MAP = "offline_map"
    private const val MAP_STYLE = "map_style"


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
        }.also { it.registerOnSharedPreferenceChangeListener(watcher) }
    }


    /**
     * The keys a hooked process acts on. A change to one of them has to reach
     * it; a change to the map style or the theme has no business waking
     * anything up.
     */
    private val HOOK_KEYS = setOf(
        ConfigKeys.STARTED, ConfigKeys.LAT, ConfigKeys.LNG,
        ConfigKeys.LAT_FLOAT, ConfigKeys.LNG_FLOAT,
        ConfigKeys.ACCURACY, ConfigKeys.JITTER_RADIUS, ConfigKeys.JITTER_MODE,
        ConfigKeys.GCJ02, ConfigKeys.ENVIRONMENT,
        ConfigKeys.SPOOF_CELL, ConfigKeys.SPOOF_WIFI,
        ConfigKeys.SPOOF_BLUETOOTH, ConfigKeys.SPOOF_TIMEZONE
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private val notifyReaders = Runnable {
        runCatching { gsApp.contentResolver.notifyChange(ConfigContract.CONTENT_URI, null) }
            .onFailure { Log.w(TAG, "could not tell hooked processes the settings changed", it) }
    }

    private const val TAG = "TeleQuant"

    /**
     * One watcher rather than a line in every setter.
     *
     * Everything that changes a setting goes through this object — the map,
     * the joystick, and the preference screen through its PreferenceDataStore —
     * so watching the file catches all of them, including the ones added
     * later. It does two things a hooked process depends on: it moves the
     * version on, which is how a reader tells two copies of the settings
     * apart, and it notifies the provider URI, which is what reaches an app
     * that is running right now without it having to poll.
     */
    private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in HOOK_KEYS) {
            pref.edit().putLong(ConfigKeys.VERSION, configVersion + 1).apply()
            // Coalesced: a single edit that writes three keys is one change.
            mainHandler.removeCallbacks(notifyReaders)
            mainHandler.postDelayed(notifyReaders, NOTIFY_DELAY_MILLIS)
        }
    }

    private const val NOTIFY_DELAY_MILLIS = 100L

    /** Goes up on every change a hooked process cares about. */
    val configVersion: Long get() = pref.getLong(ConfigKeys.VERSION, 0L)

    /**
     * Whether ConfigProvider answers at all.
     *
     * It is the only way to reach a process under a rootless framework, and it
     * is off by default because a patched app is signed with a different key
     * than this one — so the provider cannot be closed to everything else with
     * a signature permission, and an exported door that nobody needs should
     * not be standing open.
     */
    var rootlessMode: Boolean
        get() = pref.getBoolean(ROOTLESS_MODE, false)
        set(value) = pref.edit().putBoolean(ROOTLESS_MODE, value).apply()

    /** Packages that have read the settings, and when they last did. */
    val hookCheckIns: Map<String, Long>
        get() = runCatching {
            val document = JSONObject(pref.getString(HOOK_CHECK_INS, "{}").orEmpty())
            document.keys().asSequence().associateWith { document.optLong(it) }
        }.getOrDefault(emptyMap())

    fun recordHookCheckIn(packageName: String) {
        val updated = (hookCheckIns + (packageName to System.currentTimeMillis()))
            .entries.sortedByDescending { it.value }.take(MAX_CHECK_INS)
        val document = JSONObject()
        updated.forEach { document.put(it.key, it.value) }
        pref.edit().putString(HOOK_CHECK_INS, document.toString()).apply()
    }

    private const val MAX_CHECK_INS = 8

    val isStarted : Boolean
        get() = pref.getBoolean(ConfigKeys.STARTED, false)

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
        get() = readCoordinate(ConfigKeys.LAT, ConfigKeys.LAT_FLOAT, DEFAULT_LAT)

    val getLng: Double
        get() = readCoordinate(ConfigKeys.LNG, ConfigKeys.LNG_FLOAT, DEFAULT_LNG)

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
        get() = pref.getString(ConfigKeys.ACCURACY,"10")
        set(value) { pref.edit().putString(ConfigKeys.ACCURACY,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, DayNightDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    /**
     * Wander radius in metres; 0 pins the position exactly. Replaces the old
     * boolean "random position", which the jitter engine no longer reads.
     */
    var jitterRadius: String?
        get() = pref.getString(ConfigKeys.JITTER_RADIUS, "10")
        set(value) { pref.edit().putString(ConfigKeys.JITTER_RADIUS, value).apply() }

    /** One of JitterEngine.Mode: STATIONARY, WALKING, DRIVING. */
    var jitterMode: String?
        get() = pref.getString(ConfigKeys.JITTER_MODE, "STATIONARY")
        set(value) { pref.edit().putString(ConfigKeys.JITTER_MODE, value).apply() }

    /** Emit GCJ-02 instead of WGS-84. Only affects coordinates inside China. */
    var gcj02Output: Boolean
        get() = pref.getBoolean(ConfigKeys.GCJ02, false)
        set(value) = pref.edit().putBoolean(ConfigKeys.GCJ02, value).apply()

    var spoofCell: Boolean
        get() = pref.getBoolean(ConfigKeys.SPOOF_CELL, true)
        set(value) = pref.edit().putBoolean(ConfigKeys.SPOOF_CELL, value).apply()

    var spoofWifi: Boolean
        get() = pref.getBoolean(ConfigKeys.SPOOF_WIFI, true)
        set(value) = pref.edit().putBoolean(ConfigKeys.SPOOF_WIFI, value).apply()

    var spoofBluetooth: Boolean
        get() = pref.getBoolean(ConfigKeys.SPOOF_BLUETOOTH, true)
        set(value) = pref.edit().putBoolean(ConfigKeys.SPOOF_BLUETOOTH, value).apply()

    var spoofTimeZone: Boolean
        get() = pref.getBoolean(ConfigKeys.SPOOF_TIMEZONE, false)
        set(value) = pref.edit().putBoolean(ConfigKeys.SPOOF_TIMEZONE, value).apply()

    /** The active recorded environment, serialised as JSON. */
    var activeEnvironment: String?
        get() = pref.getString(ConfigKeys.ENVIRONMENT, null)
        set(value) { pref.edit().putString(ConfigKeys.ENVIRONMENT, value).apply() }

    /**
     * One proxy, chosen per destination.
     *
     * Which hosts need it is a property of the network, not of the app, and the
     * two destinations measured differently on the same connection: the tile
     * host answers directly in about 0.7 s and through the proxy in about 1.2 s,
     * while Nominatim does not answer directly at all. So the defaults are off
     * for tiles and on for geocoding — but both are the user's to change, since
     * another network will block a different set.
     */
    var proxyTiles: Boolean
        get() = pref.getBoolean(PROXY_TILES, false)
        set(value) = pref.edit().putBoolean(PROXY_TILES, value).apply()

    var proxyGeocoder: Boolean
        get() = pref.getBoolean(GEOCODER_PROXY_ENABLED, true)
        set(value) = pref.edit().putBoolean(GEOCODER_PROXY_ENABLED, value).apply()

    var proxyHost: String?
        get() = pref.getString(TILE_PROXY_HOST, "127.0.0.1")
        set(value) { pref.edit().putString(TILE_PROXY_HOST, value).apply() }

    var proxyPort: String?
        get() = pref.getString(TILE_PROXY_PORT, "33009")
        set(value) { pref.edit().putString(TILE_PROXY_PORT, value).apply() }

    /** OpenFreeMap style name: liberty, bright or positron. */
    var mapStyle: String?
        get() = pref.getString(MAP_STYLE, "liberty")
        set(value) { pref.edit().putString(MAP_STYLE, value).apply() }

    /** Serve tiles from the cache only; nothing is fetched. */
    var offlineMap: Boolean
        get() = pref.getBoolean(OFFLINE_MAP, false)
        set(value) = pref.edit().putBoolean(OFFLINE_MAP, value).apply()



    fun update(start: Boolean, la: Double, ln: Double) {
        runInBackground {
            pref.edit()
                .putLong(ConfigKeys.LAT, la.toRawBits())
                .putLong(ConfigKeys.LNG, ln.toRawBits())
                .putBoolean(ConfigKeys.STARTED, start)
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