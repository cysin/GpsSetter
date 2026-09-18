package com.cysindex.telequant.spoof

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Produces a believable wander around an anchor point, as a pure function of
 * the wall clock.
 *
 * Two earlier designs were wrong in different ways. The original drew a fresh
 * uniform offset on every read, and reads happen every few hundred
 * milliseconds: that is teleportation, not movement, and anything deriving
 * speed from successive fixes saw absurd values. Replacing it with an
 * Ornstein–Uhlenbeck walk fixed the physics but kept the state *inside the
 * process doing the walking* — and this module is loaded separately into every
 * hooked app. Two apps asking where the device was at the same instant got two
 * different answers a dozen metres apart, and every app began its walk at the
 * exact centre of the circle the moment it started.
 *
 * So the path is now a function of time rather than a process's accumulated
 * state. Every process evaluates the same function against the same clock and
 * therefore agrees, a freshly launched app joins the path already in progress,
 * and the module app can draw the position apps are actually being given
 * instead of a lookalike.
 *
 * The path itself is three sine waves per axis whose periods share no common
 * multiple, so it never visibly repeats, mapped from the square onto the disc
 * so it cannot leave the radius. It is not random — but neither is it
 * distinguishable from a wander by anything looking at it, which is what the
 * radius was for.
 */
object JitterEngine {

    enum class Mode(
        /** Seconds for the three components. Coprime, so the sum does not cycle. */
        val periodsSeconds: DoubleArray
    ) {
        STATIONARY(doubleArrayOf(97.0, 149.0, 223.0)),
        WALKING(doubleArrayOf(31.0, 47.0, 73.0)),
        DRIVING(doubleArrayOf(7.0, 11.0, 19.0))
    }

    /** Offsets are in metres, east/north of the anchor. */
    data class Sample(
        val dEastMeters: Double,
        val dNorthMeters: Double,
        val speedMps: Float,
        val bearingDeg: Float
    )

    /** Amplitudes sum to 1, so each axis stays within ±1 before mapping. */
    private val AMPLITUDES = doubleArrayOf(0.5, 0.3, 0.2)

    /** Quarter-turn apart, so the two axes are not the same curve delayed. */
    private const val PHASE_EAST = 0.0
    private const val PHASE_NORTH = PI / 2

    /** Interval used to derive speed and bearing by difference. */
    private const val DERIVATIVE_STEP_MS = 500L

    fun sample(radiusMeters: Double, mode: Mode, timeMillis: Long): Sample {
        if (radiusMeters <= 0.0) return Sample(0.0, 0.0, 0f, 0f)

        val (east, north) = offsetAt(radiusMeters, mode, timeMillis)
        val (prevEast, prevNorth) = offsetAt(radiusMeters, mode, timeMillis - DERIVATIVE_STEP_MS)

        val movedEast = east - prevEast
        val movedNorth = north - prevNorth
        val moved = hypot(movedEast, movedNorth)
        val speed = (moved / (DERIVATIVE_STEP_MS / 1000.0)).toFloat()
        // Below a few centimetres the direction is numerical noise, so hold the
        // previous heading rather than spinning the compass.
        val bearing = if (moved > 0.05) {
            ((Math.toDegrees(atan2(movedEast, movedNorth)) + 360.0) % 360.0).toFloat()
        } else {
            0f
        }
        return Sample(east, north, speed, bearing)
    }

    /**
     * The position on the path, in metres east/north of the anchor.
     *
     * The square-to-disc mapping is what keeps the walk inside the radius. The
     * earlier version reflected off the boundary instead, which only works
     * while a step is shorter than two radii — a long polling interval in
     * driving mode stepped further and put the walk back outside.
     */
    private fun offsetAt(
        radiusMeters: Double,
        mode: Mode,
        timeMillis: Long
    ): Pair<Double, Double> {
        val seconds = timeMillis / 1000.0
        val a = wave(seconds, mode, PHASE_EAST)
        val b = wave(seconds, mode, PHASE_NORTH)
        return radiusMeters * a * sqrt(1 - b * b / 2) to
                radiusMeters * b * sqrt(1 - a * a / 2)
    }

    private fun wave(seconds: Double, mode: Mode, phase: Double): Double {
        var sum = 0.0
        mode.periodsSeconds.forEachIndexed { i, period ->
            sum += AMPLITUDES[i] * sin(2 * PI * seconds / period + phase * (i + 1))
        }
        return sum
    }

    private const val EARTH_RADIUS = 6378137.0

    /** Offsets a WGS-84 coordinate by a local east/north displacement. */
    fun offset(lat: Double, lng: Double, dEast: Double, dNorth: Double): Pair<Double, Double> {
        val dLat = Math.toDegrees(dNorth / EARTH_RADIUS)
        val dLng = Math.toDegrees(dEast / (EARTH_RADIUS * cos(Math.toRadians(lat))))
        return (lat + dLat) to (lng + dLng)
    }
}

/**
 * Wobbles a signal strength around its recorded value. A constant RSSI across
 * every scan is itself a fingerprint — real radios never hold perfectly still.
 *
 * Unlike the position, this stays random per process: two apps scanning at the
 * same moment genuinely do measure slightly different strengths, and nothing
 * cross-checks one app's RSSI against another's.
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
