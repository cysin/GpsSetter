package com.cysindex.telequant.xposed.hooks

import android.location.GnssStatus
import android.location.OnNmeaMessageListener
import android.os.Handler
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.floor

/**
 * GNSS: satellite status, NMEA, and the raw-measurement paths.
 *
 * Two of these were entirely uncovered before. NMEA sentences carry the
 * position as plain text, so an app calling `addNmeaListener` read the true
 * coordinates regardless of every Location hook; and the legacy `GpsStatus`
 * API, which older apps still use, was never touched.
 *
 * The satellite set is rebuilt from [SpoofEngine] on every callback. The old
 * code constructed one GnssStatus at process start and replayed that same
 * object forever — identical satellites at identical C/N0 on every single
 * callback, which no real receiver ever produces.
 */
object GnssHooks : YukiBaseHooker() {

    override fun onHook() {
        hookGnssStatusCallback()
        hookNmea()
        hookLegacyGpsStatus()
        suppressRawMeasurements()
    }

    private fun snapshot(): SpoofEngine.Snapshot? =
        SpoofEngine.current().takeIf { it.enabled }

    /** Builds a fresh GnssStatus from the current snapshot. */
    private fun buildStatus(): GnssStatus? {
        val snapshot = snapshot() ?: return null
        if (snapshot.satellites.isEmpty()) return null
        return runCatching {
            val builder = GnssStatus.Builder()
            snapshot.satellites.forEach { sat ->
                builder.addSatellite(
                    sat.constellation,
                    sat.svid,
                    sat.cn0,
                    sat.elevation,
                    sat.azimuth,
                    sat.hasEphemeris,
                    sat.hasAlmanac,
                    sat.usedInFix,
                    true,
                    sat.carrierFrequency,
                    false,
                    0f
                )
            }
            builder.build()
        }.onFailure { YLog.warn("GnssStatus build failed: $it") }.getOrNull()
    }

    private fun hookGnssStatusCallback() {
        val lm = "android.location.LocationManager".toClass()
        if (!lm.hasMethod { name = "registerGnssStatusCallback" }) return

        lm.method { name = "registerGnssStatusCallback" }.hookAll {
            before {
                val index = args.indexOfFirst { it is GnssStatus.Callback }
                if (index < 0) return@before
                val original = args[index] as GnssStatus.Callback
                val handler = args.firstOrNull { it is Handler } as? Handler
                val executor = args.firstOrNull { it is Executor } as? Executor
                args[index] = SpoofingGnssCallback(original, handler, executor)
            }
        }

        if (lm.hasMethod { name = "getGnssStatus" }) {
            lm.method { name = "getGnssStatus" }.hookAll {
                after { buildStatus()?.let { result = it } }
            }
        }
    }

    /**
     * Rewrites the satellite set on its way to the app. Delivery stays on
     * whatever thread the framework chose, which is the one the app asked for.
     */
    private class SpoofingGnssCallback(
        private val delegate: GnssStatus.Callback,
        private val handler: Handler?,
        private val executor: Executor?
    ) : GnssStatus.Callback() {

        override fun onStarted() = delegate.onStarted()

        override fun onStopped() = delegate.onStopped()

        override fun onFirstFix(ttffMillis: Int) = delegate.onFirstFix(ttffMillis)

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val spoofed = buildStatus() ?: run {
                delegate.onSatelliteStatusChanged(status)
                return
            }
            delegate.onSatelliteStatusChanged(spoofed)
        }
    }

    /**
     * NMEA sentences embed latitude and longitude in the text itself, so this
     * is a direct leak if left alone. Rather than suppressing the stream (some
     * apps treat silence as a broken GPS), synthesise sentences that agree with
     * the spoofed fix.
     */
    private fun hookNmea() {
        val lm = "android.location.LocationManager".toClass()
        if (!lm.hasMethod { name = "addNmeaListener" }) return

        lm.method { name = "addNmeaListener" }.hookAll {
            before {
                if (snapshot() == null) return@before
                val index = args.indexOfFirst { it is OnNmeaMessageListener }
                if (index < 0) {
                    // Older overloads take a hidden NmeaListener interface;
                    // dropping those is safer than emitting a wrong format.
                    result = true
                    return@before
                }
                val original = args[index] as OnNmeaMessageListener
                args[index] = OnNmeaMessageListener { _, timestamp ->
                    val sentence = buildGga(timestamp)
                    if (sentence != null) {
                        original.onNmeaMessage(sentence, timestamp)
                    }
                }
            }
        }
    }

    /** A minimal but well-formed $GPGGA carrying the spoofed position. */
    private fun buildGga(timestamp: Long): String? {
        val snapshot = snapshot() ?: return null
        val used = snapshot.satellites.count { it.usedInFix }

        val body = buildString {
            append("GPGGA,")
            append(utcTime(timestamp)).append(',')
            append(degreesMinutes(abs(snapshot.lat), 2)).append(',')
            append(if (snapshot.lat >= 0) 'N' else 'S').append(',')
            append(degreesMinutes(abs(snapshot.lng), 3)).append(',')
            append(if (snapshot.lng >= 0) 'E' else 'W').append(',')
            append("1,")                                   // GPS fix
            append(String.format(Locale.US, "%02d,", used))
            append(String.format(Locale.US, "%.1f,", 0.9)) // HDOP
            append(String.format(Locale.US, "%.1f,M,", snapshot.altitude))
            append("0.0,M,,")
        }
        return "\$$body*${checksum(body)}"
    }

    private fun utcTime(timestamp: Long): String {
        val totalSeconds = timestamp / 1000
        val h = (totalSeconds / 3600) % 24
        val m = (totalSeconds / 60) % 60
        val s = totalSeconds % 60
        val millis = timestamp % 1000
        return String.format(Locale.US, "%02d%02d%02d.%02d", h, m, s, millis / 10)
    }

    /** NMEA encodes coordinates as degrees plus decimal minutes, not decimal degrees. */
    private fun degreesMinutes(value: Double, degreeDigits: Int): String {
        val degrees = floor(value).toInt()
        val minutes = (value - degrees) * 60.0
        return String.format(Locale.US, "%0${degreeDigits}d%07.4f", degrees, minutes)
    }

    private fun checksum(body: String): String {
        var sum = 0
        body.forEach { sum = sum xor it.code }
        return String.format(Locale.US, "%02X", sum)
    }

    /**
     * Pre-Nougat API that older apps still call. Never hooked before, so it
     * reported the genuine constellation.
     */
    private fun hookLegacyGpsStatus() {
        val lm = "android.location.LocationManager".toClass()

        @Suppress("DEPRECATION")
        if (lm.hasMethod { name = "addGpsStatusListener" }) {
            lm.method { name = "addGpsStatusListener" }.hookAll {
                before { if (snapshot() != null) result = true }
            }
        }
        if (lm.hasMethod { name = "getGpsStatus" }) {
            lm.method { name = "getGpsStatus" }.hookAll {
                before {
                    // GpsStatus cannot be populated without hidden setters whose
                    // shape varies; returning the unmodified object would leak,
                    // so hand back nothing instead.
                    if (snapshot() != null) result = null
                }
            }
        }
    }

    /**
     * Raw measurements (pseudoranges, Doppler, carrier phase, navigation
     * messages) cannot be faked into a self-consistent set — an inconsistent
     * one is more revealing than no data. Suppressed deliberately.
     */
    private fun suppressRawMeasurements() {
        val lm = "android.location.LocationManager".toClass()
        listOf(
            "registerGnssMeasurementsCallback",
            "addGnssMeasurementsListener",
            "registerGnssNavigationMessageCallback",
            "addGnssNavigationMessageListener",
            "registerAntennaInfoListener"
        ).forEach { methodName ->
            if (!lm.hasMethod { name = methodName }) return@forEach
            lm.method { name = methodName }.hookAll {
                before {
                    if (snapshot() != null) {
                        result = false
                        YLog.debug("[$packageName] suppressed $methodName")
                    }
                }
            }
        }
    }
}
