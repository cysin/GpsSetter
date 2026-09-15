package com.cysindex.telequant.spoof

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Produces a believable wander around an anchor point.
 *
 * The previous implementation drew a fresh uniform offset on every read (and
 * reads happened every ~200 ms), which is teleportation, not movement:
 * consecutive fixes were uncorrelated, so any consumer computing speed from
 * successive points saw wild values.
 *
 * This is a discrete Ornstein–Uhlenbeck process — a random walk with a pull
 * back toward the anchor — clamped to [radiusMeters]. Successive samples are
 * correlated, so speed and bearing derived from them are physically sane, and
 * the walk stays bounded instead of drifting away.
 */
class JitterEngine(
    private val radiusMeters: Double,
    private val mode: Mode = Mode.STATIONARY,
    private val random: Random = Random.Default
) {

    enum class Mode(
        /** Pull back toward the anchor, per second. Higher = tighter leash. */
        val meanReversion: Double,
        /** Random push per sqrt(second), in metres. Higher = faster wander. */
        val volatility: Double
    ) {
        STATIONARY(0.45, 0.35),
        WALKING(0.15, 1.10),
        DRIVING(0.05, 6.00)
    }

    /** Offsets are in metres, east/north of the anchor. */
    data class Sample(
        val dEastMeters: Double,
        val dNorthMeters: Double,
        val speedMps: Float,
        val bearingDeg: Float
    )

    private var east = 0.0
    private var north = 0.0
    private var lastNanos = 0L
    private var lastSpeed = 0f
    private var lastBearing = 0f

    @Synchronized
    fun sample(nowNanos: Long): Sample {
        if (radiusMeters <= 0.0) return Sample(0.0, 0.0, 0f, 0f)

        if (lastNanos == 0L) {
            lastNanos = nowNanos
            return Sample(0.0, 0.0, 0f, 0f)
        }

        // Clamp dt: a process that was idle for minutes should not take one
        // enormous step, and a burst of reads in the same millisecond should
        // not all return the identical point.
        val dt = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.01, 5.0)
        lastNanos = nowNanos

        val prevEast = east
        val prevNorth = north

        val decay = mode.meanReversion * dt
        val kick = mode.volatility * sqrt(dt)
        east += -decay * east + kick * random.gaussian()
        north += -decay * north + kick * random.gaussian()

        // Keep the walk inside the circle by reflecting rather than hard
        // clipping; clipping would make the boundary a visible attractor.
        val dist = hypot(east, north)
        if (dist > radiusMeters && dist > 0.0) {
            val scale = (2 * radiusMeters - dist) / dist
            east *= scale
            north *= scale
        }

        val movedEast = east - prevEast
        val movedNorth = north - prevNorth
        val moved = hypot(movedEast, movedNorth)
        lastSpeed = (moved / dt).toFloat()
        if (moved > 0.05) {
            lastBearing = ((Math.toDegrees(atan2(movedEast, movedNorth)) + 360.0) % 360.0).toFloat()
        }

        return Sample(east, north, lastSpeed, lastBearing)
    }

    companion object {
        private const val EARTH_RADIUS = 6378137.0

        /** Offsets a WGS-84 coordinate by a local east/north displacement. */
        fun offset(lat: Double, lng: Double, dEast: Double, dNorth: Double): Pair<Double, Double> {
            val dLat = Math.toDegrees(dNorth / EARTH_RADIUS)
            val dLng = Math.toDegrees(dEast / (EARTH_RADIUS * cos(Math.toRadians(lat))))
            return (lat + dLat) to (lng + dLng)
        }
    }
}

/** Box–Muller; [Random] has no Gaussian of its own. */
private fun Random.gaussian(): Double {
    var u: Double
    do {
        u = nextDouble()
    } while (u <= Double.MIN_VALUE)
    return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * nextDouble())
}

/**
 * Wobbles a signal strength around its recorded value. A constant RSSI across
 * every scan is itself a fingerprint — real radios never hold perfectly still.
 */
fun jitterDbm(base: Int, spreadDb: Int = 3, random: Random = Random.Default): Int {
    val delta = random.nextInt(-spreadDb, spreadDb + 1)
    return base + delta
}

/** Same idea for satellite carrier-to-noise density. */
fun jitterCn0(base: Float, spread: Float = 1.5f, random: Random = Random.Default): Float {
    val delta = (random.nextDouble() * 2 - 1) * spread
    return max(0f, base + delta.toFloat())
}

internal fun wrapDegrees(v: Double): Double = ((v % 360.0) + 360.0) % 360.0

internal fun approximately(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps

internal fun sinDeg(deg: Double) = sin(Math.toRadians(deg))
