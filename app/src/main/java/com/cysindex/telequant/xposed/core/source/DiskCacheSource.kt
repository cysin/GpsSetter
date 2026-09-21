package com.cysindex.telequant.xposed.core.source

import android.content.Context
import com.cysindex.telequant.xposed.core.Config
import com.cysindex.telequant.config.ConfigKeys
import com.cysindex.telequant.xposed.core.ConfigReader
import org.json.JSONObject
import java.io.File

/**
 * The last settings this process was given, kept in the app's own storage.
 *
 * It exists for the gap the other two routes leave open. A file the hook may
 * not read and a module App that is not running both answer the same way —
 * nothing — and without a copy of its own the process would fall back to
 * spoofing off, which means the app abruptly learns where the device really
 * is. That is the one failure mode worth spending a file to avoid: a phone
 * killed the App in the background, or the user swiped it away, and the fix
 * simply carries on from what was last set.
 *
 * It is written only when the value actually changes, so a running process
 * touches the disk about as often as the settings are edited.
 */
internal class DiskCacheSource(private val context: () -> Context?) : ConfigSource {

    override val name = "cached copy"

    private var cached: Config? = null
    private var readAtModified = -1L

    @Synchronized
    override fun read(): Config? {
        val file = file() ?: return null
        if (!file.isFile) return null
        val modified = file.lastModified()
        val current = cached
        if (current != null && modified == readAtModified) return current
        return runCatching {
            Config.from(JsonReader(JSONObject(file.readText())))
        }.getOrNull()?.also {
            cached = it
            readAtModified = modified
        }
    }

    @Synchronized
    fun store(config: Config) {
        if (config == cached) return
        val file = file() ?: return
        runCatching {
            val document = JSONObject()
                .put(ConfigKeys.STARTED, config.started)
                .put(ConfigKeys.LAT, config.lat.toRawBits())
                .put(ConfigKeys.LNG, config.lng.toRawBits())
                .put(ConfigKeys.ACCURACY, config.accuracy.toString())
                .put(ConfigKeys.JITTER_RADIUS, config.jitterRadiusMeters.toString())
                .put(ConfigKeys.JITTER_MODE, config.jitterMode)
                .put(ConfigKeys.GCJ02, config.gcj02Output)
                .put(ConfigKeys.SPOOF_CELL, config.spoofCell)
                .put(ConfigKeys.SPOOF_WIFI, config.spoofWifi)
                .put(ConfigKeys.SPOOF_BLUETOOTH, config.spoofBluetooth)
                .put(ConfigKeys.SPOOF_TIMEZONE, config.spoofTimeZone)
                .put(ConfigKeys.VERSION, config.version)
            config.environmentJson?.let { document.put(ConfigKeys.ENVIRONMENT, it) }

            // Through a temporary file: a process killed mid-write would
            // otherwise leave a truncated document, and the next start would
            // read it as no settings at all.
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(document.toString())
            if (!temporary.renameTo(file)) temporary.delete()
            cached = config
            readAtModified = file.lastModified()
        }
    }

    /** Kept out of backups: it is a record of where its owner has been. */
    private fun file(): File? = context()?.let { File(it.noBackupFilesDir, FILE_NAME) }

    private class JsonReader(private val document: JSONObject) : ConfigReader {
        override fun contains(key: String) = document.has(key)
        override fun bool(key: String, fallback: Boolean) = document.optBoolean(key, fallback)
        override fun long(key: String, fallback: Long) = document.optLong(key, fallback)
        override fun float(key: String, fallback: Float) =
            document.optDouble(key, fallback.toDouble()).toFloat()
        override fun string(key: String): String? =
            if (document.isNull(key)) null else document.optString(key, "").ifEmpty { null }
    }

    private companion object {
        const val FILE_NAME = "telequant-config.json"
    }
}
