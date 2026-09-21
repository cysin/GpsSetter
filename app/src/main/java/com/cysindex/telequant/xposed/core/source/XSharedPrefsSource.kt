package com.cysindex.telequant.xposed.core.source

import com.cysindex.telequant.xposed.core.Config
import com.cysindex.telequant.xposed.core.ConfigReader
import de.robv.android.xposed.XSharedPreferences

/**
 * The settings as a file, which is how they arrive under a rooted framework.
 *
 * Vector's daemon answers `getPrefsPath` with a world-readable directory it
 * keeps outside the module App, so the file is genuinely open to the hooked
 * process. LSPatch answers the same call with the module App's own private
 * directory (`/data/data/<module>/shared_prefs/`), which is mode 0700 — there
 * the open fails and XSharedPreferences reports every value as absent rather
 * than raising anything. That silence is why [read] checks the file itself
 * instead of trusting the values it gets back.
 */
internal class XSharedPrefsSource(
    modulePackageName: String,
    prefsFileName: String
) : ConfigSource {

    override val name = "XSharedPreferences"

    private val prefs = XSharedPreferences(modulePackageName, prefsFileName)

    private val reader = object : ConfigReader {
        override fun contains(key: String) = prefs.contains(key)
        override fun bool(key: String, fallback: Boolean) = prefs.getBoolean(key, fallback)
        override fun long(key: String, fallback: Long) = prefs.getLong(key, fallback)
        override fun float(key: String, fallback: Float) = prefs.getFloat(key, fallback)
        override fun string(key: String): String? = prefs.getString(key, null)
    }

    private var cached: Config? = null

    @Synchronized
    override fun read(): Config? {
        if (!isReadable()) return null
        val current = cached
        if (current != null && !prefs.hasFileChanged()) return current
        prefs.reload()
        return Config.from(reader).also { cached = it }
    }

    private fun isReadable() = runCatching { prefs.file.canRead() }.getOrDefault(false)
}
