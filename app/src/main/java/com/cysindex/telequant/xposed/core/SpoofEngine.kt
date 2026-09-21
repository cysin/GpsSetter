package com.cysindex.telequant.xposed.core

import android.os.SystemClock
import com.cysindex.telequant.spoof.BeaconRecord
import com.cysindex.telequant.spoof.CellRecord
import com.cysindex.telequant.spoof.CoordinateTransform
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.spoof.JitterEngine
import com.cysindex.telequant.spoof.SatelliteRecord
import com.cysindex.telequant.spoof.WifiRecord
import com.cysindex.telequant.spoof.jitterCn0
import com.cysindex.telequant.spoof.jitterDbm
import kotlin.random.Random

/**
 * The single source of truth for every hook in this module.
 *
 * The central invariant: a hook never invents a value and never rolls its own
 * dice. It asks for [current] and reads one field. Because position, cells,
 * Wi-Fi, beacons, satellites and operator identity all come out of the same
 * [Snapshot], they cannot contradict each other.
 *
 * That is precisely what the previous design could not do — GPS ran on one set
 * of random numbers, cell identity was hardcoded to MCC 460 with every other
 * field zeroed, and Wi-Fi was blanked to empty strings. The three had nothing
 * to do with one another, so the position said one thing and the radio
 * environment said something incompatible.
 */
object SpoofEngine {

    /** How long a snapshot stays valid. Short enough to feel live, long
     *  enough that a burst of hook calls within one fix agrees with itself. */
    private const val SNAPSHOT_TTL_NANOS = 250_000_000L // 250 ms

    data class Snapshot(
        val enabled: Boolean,
        val lat: Double,
        val lng: Double,
        val altitude: Double,
        val accuracy: Float,
        val speedMps: Float,
        val bearingDeg: Float,
        val elapsedRealtimeNanos: Long,
        val timeMillis: Long,
        val cells: List<CellRecord>,
        val wifis: List<WifiRecord>,
        val beacons: List<BeaconRecord>,
        val satellites: List<SatelliteRecord>,
        val operatorName: String?,
        val operatorNumeric: String?,
        val countryIso: String?,
        val networkType: Int,
        val timeZoneId: String?,
        val spoofCell: Boolean,
        val spoofWifi: Boolean,
        val spoofBluetooth: Boolean,
        val spoofTimeZone: Boolean
    ) {
        // The per-signal switches are folded in here rather than read
        // separately by each hook, so a hook cannot act on a stale toggle while
        // the rest of the snapshot reflects a newer one.
        val hasCells get() = spoofCell && cells.isNotEmpty()
        val hasWifis get() = spoofWifi && wifis.isNotEmpty()
        val hasBeacons get() = spoofBluetooth && beacons.isNotEmpty()
    }

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedAtNanos = 0L

    private val random = Random.Default

    val isEnabled: Boolean get() = PrefsBridge.config().started

    fun current(): Snapshot {
        val now = SystemClock.elapsedRealtimeNanos()
        cached?.let { if (now - cachedAtNanos < SNAPSHOT_TTL_NANOS) return it }
        return build(now).also {
            cached = it
            cachedAtNanos = now
        }
    }

    /** Drops the cache so the next read reflects freshly changed settings. */
    fun invalidate() {
        cached = null
    }

    private fun build(nowNanos: Long): Snapshot {
        // Read once. Every field below comes from this one view of the
        // settings, so a snapshot cannot be assembled half from before a change
        // and half from after it.
        val config = PrefsBridge.config()
        val enabled = config.started
        val env = PrefsBridge.environment(config)

        // A recorded environment carries its own coordinates; a bare point
        // comes from the map. The recorded one wins when present so that the
        // radio data and the position describe the same place.
        // A recording made indoors often has no GPS fix, in which case its
        // coordinates are 0,0 rather than absent. Taking that literally would
        // silently move the anchor into the Gulf of Guinea and throw away the
        // point the user chose, so only a recording that actually got a fix is
        // allowed to override it.
        val anchorLat = env?.lat?.takeIf { env.hasFix() } ?: config.lat
        val anchorLng = env?.lng?.takeIf { env.hasFix() } ?: config.lng

        val radius = config.jitterRadiusMeters
        val mode = runCatching { JitterEngine.Mode.valueOf(config.jitterMode) }
            .getOrDefault(JitterEngine.Mode.STATIONARY)

        // Evaluated against the wall clock rather than accumulated here: this
        // object is a per-process singleton, and the module is loaded into every
        // hooked app, so state kept here made each app wander independently.
        val sample = JitterEngine.sample(radius, mode, System.currentTimeMillis())

        var (lat, lng) = JitterEngine.offset(
            anchorLat, anchorLng, sample.dEastMeters, sample.dNorthMeters
        )

        // Applied last, and only inside its area of validity.
        if (config.gcj02Output) {
            val converted = CoordinateTransform.wgs84ToGcj02(lat, lng)
            lat = converted.first
            lng = converted.second
        }

        return Snapshot(
            enabled = enabled,
            lat = lat,
            lng = lng,
            altitude = env?.altitude ?: 0.0,
            // The setting, not the environment. The recorder no longer reads a
            // position, so a recording's accuracy is a placeholder rather than
            // a measurement, and preferring it left the settings value with no
            // effect at all.
            accuracy = config.accuracy,
            speedMps = sample.speedMps,
            bearingDeg = sample.bearingDeg,
            // Never leave this at 0: consumers treat elapsedRealtimeNanos as the
            // fix's age, and 0 reads as "oldest possible since boot".
            elapsedRealtimeNanos = nowNanos,
            timeMillis = System.currentTimeMillis(),
            cells = env?.cells.orEmpty().map { it.withJitter() },
            wifis = env?.wifis.orEmpty().map { it.withJitter() },
            beacons = env?.beacons.orEmpty().map { it.withJitter() },
            satellites = (env?.satellites?.takeIf { it.isNotEmpty() } ?: synthesiseSatellites())
                .map { it.withJitter() },
            operatorName = env?.operatorName,
            operatorNumeric = env?.operatorNumeric ?: env?.cells?.firstOrNull()?.numeric(),
            countryIso = env?.countryIso,
            networkType = env?.networkType ?: 0,
            timeZoneId = env?.timeZoneId?.takeIf { config.spoofTimeZone },
            spoofCell = config.spoofCell,
            spoofWifi = config.spoofWifi,
            spoofBluetooth = config.spoofBluetooth,
            spoofTimeZone = config.spoofTimeZone
        )
    }

    private fun CellRecord.numeric(): String? {
        val mccPart = mcc ?: return null
        val mncPart = mnc ?: return null
        return "%03d%02d".format(mccPart, mncPart)
    }

    private fun CellRecord.withJitter() = copy(dbm = jitterDbm(dbm, 2, random))

    private fun WifiRecord.withJitter() = copy(level = jitterDbm(level, 3, random))

    private fun BeaconRecord.withJitter() = copy(rssi = jitterDbm(rssi, 4, random))

    private fun SatelliteRecord.withJitter() = copy(cn0 = jitterCn0(cn0, 1.5f, random))

    /**
     * Used when no environment has been recorded. Regenerated per snapshot so
     * the constellation drifts: the old code built one GnssStatus at process
     * start and replayed it forever, which meant the identical satellites with
     * identical C/N0 on every callback — a giveaway on its own.
     */
    private fun synthesiseSatellites(): List<SatelliteRecord> {
        val count = random.nextInt(7, 14)
        val used = mutableSetOf<Pair<Int, Int>>()
        return (0 until count).mapNotNull {
            val constellation = CONSTELLATIONS.random(random)
            val svid = svidRange(constellation).random(random)
            if (!used.add(constellation to svid)) return@mapNotNull null
            val elevation = random.nextDouble(5.0, 88.0).toFloat()
            val cn0 = random.nextDouble(22.0, 48.0).toFloat()
            SatelliteRecord(
                constellation = constellation,
                svid = svid,
                cn0 = cn0,
                elevation = elevation,
                azimuth = random.nextDouble(0.0, 360.0).toFloat(),
                usedInFix = elevation > 15 && cn0 > 25,
                hasEphemeris = true,
                hasAlmanac = true,
                carrierFrequency = carrierFor(constellation)
            )
        }
    }

    // GnssStatus.CONSTELLATION_* : GPS=1, GLONASS=3, BEIDOU=5, QZSS=4, GALILEO=6
    private val CONSTELLATIONS = listOf(1, 1, 1, 3, 5, 6, 4)

    private fun svidRange(constellation: Int): IntRange = when (constellation) {
        1 -> 1..32
        3 -> 1..24
        4 -> 193..200
        5 -> 1..63
        6 -> 1..36
        else -> 1..40
    }

    private fun carrierFor(constellation: Int): Float = when (constellation) {
        1 -> 1575.42f
        3 -> 1602.0f
        5 -> 1561.098f
        6 -> 1575.42f
        else -> 1575.42f
    }
}

private fun IntRange.random(random: Random): Int = random.nextInt(first, last + 1)

private fun <T> List<T>.random(random: Random): T = this[random.nextInt(size)]
