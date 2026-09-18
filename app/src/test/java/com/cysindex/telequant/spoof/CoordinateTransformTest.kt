package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * GCJ-02 is applied on the way out, so a mistake here moves every fix by a few
 * hundred metres while the UI keeps showing the point the user picked — the
 * kind of error that looks like the hooks failing.
 */
class CoordinateTransformTest {

    private val beijing = 39.9042 to 116.4074
    private val shanghai = 31.2304 to 121.4737
    private val newYork = 40.7128 to -74.0060

    @Test
    fun `china is inside the boundary and elsewhere is not`() {
        assertFalse(CoordinateTransform.outOfChina(beijing.first, beijing.second))
        assertFalse(CoordinateTransform.outOfChina(shanghai.first, shanghai.second))
        assertTrue(CoordinateTransform.outOfChina(newYork.first, newYork.second))
        assertTrue(CoordinateTransform.outOfChina(51.5074, -0.1278))    // London
        assertTrue(CoordinateTransform.outOfChina(35.6762, 139.6503))   // Tokyo
    }

    @Test
    fun `outside china the coordinate is left alone`() {
        // The original code applied the offset unconditionally, which corrupts
        // every coordinate that was already correct.
        val (lat, lng) = CoordinateTransform.wgs84ToGcj02(newYork.first, newYork.second)
        assertEquals(newYork.first, lat, 0.0)
        assertEquals(newYork.second, lng, 0.0)
    }

    @Test
    fun `inside china the shift is real but plausible`() {
        listOf(beijing, shanghai).forEach { (wgsLat, wgsLng) ->
            val (lat, lng) = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLng)
            val shiftLat = abs(lat - wgsLat)
            val shiftLng = abs(lng - wgsLng)
            // Hundreds of metres, not kilometres and not nothing.
            assertTrue("no shift applied", shiftLat > 1e-4 && shiftLng > 1e-4)
            assertTrue("shift is implausibly large", shiftLat < 0.01 && shiftLng < 0.01)
        }
    }

    @Test
    fun `the inverse returns the original point`() {
        listOf(beijing, shanghai, 23.1291 to 113.2644).forEach { (wgsLat, wgsLng) ->
            val (gcjLat, gcjLng) = CoordinateTransform.wgs84ToGcj02(wgsLat, wgsLng)
            val (backLat, backLng) = CoordinateTransform.gcj02ToWgs84(gcjLat, gcjLng)
            // 1e-6 degrees is about 11 cm.
            assertEquals(wgsLat, backLat, 1e-6)
            assertEquals(wgsLng, backLng, 1e-6)
        }
    }
}
