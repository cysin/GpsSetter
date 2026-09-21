package com.cysindex.telequant.config

/**
 * The keys the module App writes and the hooked process reads.
 *
 * This is the contract between the two sides, so it lives in one place rather
 * than being spelled out again in [com.cysindex.telequant.utils.PrefManager].
 * Every transport carries the same names: the preferences file under Vector,
 * the provider Bundle under LSPatch, and the on-disk fallback copy.
 */
object ConfigKeys {
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
