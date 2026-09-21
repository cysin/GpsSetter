package com.cysindex.telequant.xposed.core

import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.spoof.FakeEnvironment
import de.robv.android.xposed.XSharedPreferences

/**
 * The hooked process's view of the module's settings.
 *
 * Hooks like `Location.getLatitude` fire at very high rates, so nothing here
 * may touch a file — let alone a binder — per call. One [Config] is kept and
 * rebuilt only when [XSharedPreferences.hasFileChanged] says the file actually
 * changed; the parsed [FakeEnvironment] is cached alongside it and re-parsed on
 * the same signal rather than per access.
 */
internal object PrefsBridge {

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
    }

    private val reader = object : ConfigReader {
        override fun contains(key: String) = prefs.contains(key)
        override fun bool(key: String, fallback: Boolean) = prefs.getBoolean(key, fallback)
        override fun long(key: String, fallback: Long) = prefs.getLong(key, fallback)
        override fun float(key: String, fallback: Float) = prefs.getFloat(key, fallback)
        override fun string(key: String): String? = prefs.getString(key, null)
    }

    private var cached: Config? = null
    private var cachedEnvironmentJson: String? = null
    private var cachedEnvironment: FakeEnvironment? = null

    @Synchronized
    fun config(): Config {
        val current = cached
        if (current != null && !prefs.hasFileChanged()) return current
        prefs.reload()
        return Config.from(reader).also { cached = it }
    }

    /** The active recorded environment, or null when only a bare point is set. */
    @Synchronized
    fun environment(config: Config): FakeEnvironment? {
        val json = config.environmentJson
        if (json != cachedEnvironmentJson) {
            cachedEnvironmentJson = json
            cachedEnvironment = FakeEnvironment.parse(json)
        }
        return cachedEnvironment
    }
}
