package com.cysindex.telequant.spoof

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 <-> GCJ-02 ("Mars coordinates").
 *
 * Everything in this project — the map UI, the stored anchor, the recorded
 * environments — is WGS-84. GCJ-02 is applied only as an optional transform at
 * the moment a coordinate is handed to a hooked app, for the case where the app
 * expects the offset system used inside mainland China.
 *
 * Two things the previous implementation got wrong: it applied the transform
 * unconditionally (`if (true)`) with no bounds check, so coordinates everywhere
 * on Earth were shifted by a formula only valid inside China; and the map drew
 * the un-shifted point, so the marker and the injected value silently differed
 * by a few hundred metres.
 */
object CoordinateTransform {

    private const val EARTH_RADIUS = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * Rough mainland-China bounding box. GCJ-02 is undefined outside it, and
     * applying the offset there corrupts otherwise correct coordinates.
     */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        val (dLat, dLng) = delta(lat, lng)
        return (lat + dLat) to (lng + dLng)
    }

    /**
     * Inverse by iteration: GCJ-02 has no closed form, so converge on the
     * WGS-84 point whose forward transform lands on the input.
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wgsLat = lat
        var wgsLng = lng
        repeat(MAX_ITERATIONS) {
            val (fLat, fLng) = wgs84ToGcj02(wgsLat, wgsLng)
            val errLat = fLat - lat
            val errLng = fLng - lng
            if (abs(errLat) < EPSILON && abs(errLng) < EPSILON) return wgsLat to wgsLng
            wgsLat -= errLat
            wgsLng -= errLng
        }
        return wgsLat to wgsLng
    }

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        val x = lng - 105.0
        val y = lat - 35.0
        val dLatRaw = transformLat(x, y)
        val dLngRaw = transformLng(x, y)

        val radLat = Math.toRadians(lat)
        val magic = 1 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)

        val dLat = (dLatRaw * 180.0) / ((EARTH_RADIUS * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val dLng = (dLngRaw * 180.0) / (EARTH_RADIUS / sqrtMagic * cos(radLat) * Math.PI)
        return dLat to dLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private const val MAX_ITERATIONS = 8
    private const val EPSILON = 1e-9
}
