package com.cysindex.telequant.xposed.core

import android.location.Location
import android.os.Build
import android.os.Bundle

/**
 * The one place a spoofed [Location] is produced or an existing one is rewritten.
 *
 * Centralising this matters because the old code only hooked the *getters*.
 * The underlying fields stayed real, so anything reading the object rather than
 * calling its accessors — `toString()`, `writeToParcel()`, `distanceTo()`,
 * `equals()`, or reflective serialisation by a JSON library — saw the true
 * coordinates. [applyTo] rewrites the object itself, so field and getter agree.
 */
object LocationFactory {

    /** Extra used by several Chinese OEM ROMs to carry an unshifted WGS-84 fix. */
    private const val EXTRA_NO_GPS_LOCATION = "noGPSLocation"
    private const val EXTRA_MOCK_LOCATION = "mockLocation"
    private const val EXTRA_SATELLITES = "satellites"

    fun build(provider: String): Location = Location(provider).also { applyTo(it) }

    /**
     * Overwrites [location] in place with the current snapshot. Returns false
     * when spoofing is off, so callers can leave the original untouched.
     */
    fun applyTo(location: Location?): Boolean {
        if (location == null) return false
        val snapshot = SpoofEngine.current()
        if (!snapshot.enabled) return false

        location.latitude = snapshot.lat
        location.longitude = snapshot.lng
        location.accuracy = snapshot.accuracy
        applyAltitude(location, snapshot)
        location.speed = snapshot.speedMps
        location.bearing = snapshot.bearingDeg
        location.time = snapshot.timeMillis
        // 0 here reads as "the oldest fix since boot" to anything that ages a
        // location off SystemClock.elapsedRealtimeNanos(), which GMS and most
        // location SDKs do.
        location.elapsedRealtimeNanos = snapshot.elapsedRealtimeNanos

        location.speedAccuracyMetersPerSecond = 0.35f
        location.bearingAccuracyDegrees = 12f

        clearMockMarkers(location)
        rewriteExtras(location, snapshot)
        return true
    }

    /**
     * Reports an altitude only when the environment actually carries one.
     *
     * Since the recorder stopped reading a position it has no altitude to
     * record, so this was setting exactly 0.0 — and Location.setAltitude also
     * raises hasAltitude(), so every fix claimed to be a measurement taken at
     * sea level wherever in the world it was. No altitude at all is ordinary;
     * sea level everywhere is not.
     */
    private fun applyAltitude(location: Location, snapshot: SpoofEngine.Snapshot) {
        if (snapshot.altitude != 0.0) {
            location.altitude = snapshot.altitude
            location.verticalAccuracyMeters = snapshot.accuracy * 1.6f
        } else {
            @Suppress("DEPRECATION")
            runCatching { location.removeAltitude() }
            @Suppress("DEPRECATION")
            runCatching { location.removeVerticalAccuracy() }
        }
    }

    private fun clearMockMarkers(location: Location) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            }
        }
    }

    private fun rewriteExtras(location: Location, snapshot: SpoofEngine.Snapshot) {
        val extras = location.extras ?: Bundle()

        if (extras.containsKey(EXTRA_MOCK_LOCATION)) {
            extras.putBoolean(EXTRA_MOCK_LOCATION, false)
        }

        // Left alone this is a plain-text leak of the real position on ROMs that
        // populate it, regardless of how thoroughly the outer object is spoofed.
        if (extras.containsKey(EXTRA_NO_GPS_LOCATION)) {
            val inner = runCatching {
                @Suppress("DEPRECATION")
                extras.getParcelable(EXTRA_NO_GPS_LOCATION) as? Location
            }.getOrNull()
            if (inner != null) {
                inner.latitude = snapshot.lat
                inner.longitude = snapshot.lng
                inner.accuracy = snapshot.accuracy
                applyAltitude(inner, snapshot)
                inner.time = snapshot.timeMillis
                inner.elapsedRealtimeNanos = snapshot.elapsedRealtimeNanos
                extras.putParcelable(EXTRA_NO_GPS_LOCATION, inner)
            }
        }

        // Keep the satellite count consistent with what the GNSS hooks report.
        if (extras.containsKey(EXTRA_SATELLITES)) {
            extras.putInt(EXTRA_SATELLITES, snapshot.satellites.count { it.usedInFix })
        }

        if (!extras.isEmpty) location.extras = extras
    }
}
