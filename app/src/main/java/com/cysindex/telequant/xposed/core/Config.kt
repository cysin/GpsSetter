package com.cysindex.telequant.xposed.core

/**
 * The keys the module App writes and the hooked process reads.
 *
 * This is the contract between the two sides, so it lives in one place rather
 * than being spelled out again in [com.cysindex.telequant.utils.PrefManager].
 * Every transport carries the same names: the preferences file under Vector,
 * the provider Bundle under LSPatch, and the on-disk fallback copy.
 */
internal object ConfigKeys {
    const val STARTED = "start"
    // Doubles stored as raw bits; the *_FLOAT names are what older builds wrote.
    const val LAT = "latitude_d"
    const val LNG = "longitude_d"
    const val LAT_FLOAT = "latitude"
    const val LNG_FLOAT = "longitude"
    const val ACCURACY = "accuracy_settings"
    const val JITTER_RADIUS = "jitter_radius"
    const val JITTER_MODE = "jitter_mode"
    const val GCJ02 = "gcj02_output"
    const val ENVIRONMENT = "active_environment"
    const val SPOOF_CELL = "spoof_cell"
    const val SPOOF_WIFI = "spoof_wifi"
    const val SPOOF_BLUETOOTH = "spoof_bluetooth"
    const val SPOOF_TIMEZONE = "spoof_timezone"

    /**
     * Bumped by the module App on every write. It is what lets a reader decide
     * whether anything actually changed without comparing a whole environment
     * document, and which of two copies of the settings is the newer one.
     */
    const val VERSION = "config_version"
}

/**
 * A key/value store seen through the narrow window [Config] needs.
 *
 * The settings reach a hooked process by three different routes, and each one
 * used to imply its own copy of "what does this key mean, and what is it when
 * absent". Reading them all through this interface keeps the defaults and the
 * legacy-key handling in exactly one place.
 */
internal interface ConfigReader {
    fun contains(key: String): Boolean
    fun bool(key: String, fallback: Boolean): Boolean
    fun long(key: String, fallback: Long): Long
    fun float(key: String, fallback: Float): Float
    fun string(key: String): String?
}

/**
 * One coherent view of the module's settings.
 *
 * Hooks used to read each setting individually, which meant a single snapshot
 * could be assembled from two generations of the settings — the anchor from
 * before a change and the jitter radius from after it. A value type read once
 * cannot do that. It also matters for what comes next: a source that answers
 * over a binder cannot be asked once per field per fix.
 */
internal data class Config(
    val started: Boolean,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val jitterRadiusMeters: Double,
    val jitterMode: String,
    val gcj02Output: Boolean,
    val spoofCell: Boolean,
    val spoofWifi: Boolean,
    val spoofBluetooth: Boolean,
    val spoofTimeZone: Boolean,
    val environmentJson: String?,
    val version: Long
) {
    companion object {
        const val DEFAULT_LAT = 40.7128
        const val DEFAULT_LNG = -74.0060

        /** What a process sees when no settings can be read at all. Spoofing
         *  off is the only safe answer: the alternative is an app being told it
         *  is in New York because a file could not be opened. */
        val DEFAULT = Config(
            started = false,
            lat = DEFAULT_LAT,
            lng = DEFAULT_LNG,
            accuracy = 10f,
            jitterRadiusMeters = 10.0,
            jitterMode = "STATIONARY",
            gcj02Output = false,
            spoofCell = true,
            spoofWifi = true,
            spoofBluetooth = true,
            spoofTimeZone = false,
            environmentJson = null,
            version = 0L
        )

        fun from(reader: ConfigReader): Config = with(reader) {
            Config(
                started = bool(ConfigKeys.STARTED, DEFAULT.started),
                lat = coordinate(ConfigKeys.LAT, ConfigKeys.LAT_FLOAT, DEFAULT_LAT),
                lng = coordinate(ConfigKeys.LNG, ConfigKeys.LNG_FLOAT, DEFAULT_LNG),
                accuracy = number(ConfigKeys.ACCURACY, DEFAULT.accuracy.toDouble()).toFloat(),
                jitterRadiusMeters = number(ConfigKeys.JITTER_RADIUS, DEFAULT.jitterRadiusMeters),
                jitterMode = string(ConfigKeys.JITTER_MODE) ?: DEFAULT.jitterMode,
                gcj02Output = bool(ConfigKeys.GCJ02, DEFAULT.gcj02Output),
                spoofCell = bool(ConfigKeys.SPOOF_CELL, DEFAULT.spoofCell),
                spoofWifi = bool(ConfigKeys.SPOOF_WIFI, DEFAULT.spoofWifi),
                spoofBluetooth = bool(ConfigKeys.SPOOF_BLUETOOTH, DEFAULT.spoofBluetooth),
                spoofTimeZone = bool(ConfigKeys.SPOOF_TIMEZONE, DEFAULT.spoofTimeZone),
                environmentJson = string(ConfigKeys.ENVIRONMENT),
                version = long(ConfigKeys.VERSION, DEFAULT.version)
            )
        }

        /** Float storage cost about a metre of resolution; see PrefManager. */
        private fun ConfigReader.coordinate(
            key: String,
            legacyKey: String,
            fallback: Double
        ): Double = when {
            contains(key) -> Double.fromBits(long(key, fallback.toRawBits()))
            contains(legacyKey) -> float(legacyKey, fallback.toFloat()).toDouble()
            else -> fallback
        }

        /** Accuracy and the jitter radius come from EditTextPreferences, so
         *  they are strings — and a string a person typed can be anything. */
        private fun ConfigReader.number(key: String, fallback: Double): Double =
            string(key)?.trim()?.toDoubleOrNull() ?: fallback
    }
}
