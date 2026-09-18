package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The walk is a pure function of the clock, so these are exact expectations
 * rather than statistical ones.
 */
class JitterEngineTest {

    private val start = 1_700_000_000_000L

    private fun path(
        radius: Double,
        mode: JitterEngine.Mode,
        steps: Int = 4_000,
        stepMillis: Long = 200L
    ): List<JitterEngine.Sample> =
        (0 until steps).map { JitterEngine.sample(radius, mode, start + it * stepMillis) }

    @Test
    fun `stays inside the radius`() {
        JitterEngine.Mode.entries.forEach { mode ->
            path(radius = 10.0, mode = mode).forEachIndexed { i, sample ->
                val distance = hypot(sample.dEastMeters, sample.dNorthMeters)
                assertTrue(
                    "$mode escaped at step $i: ${"%.3f".format(distance)}m > 10m",
                    distance <= 10.0 + 1e-9
                )
            }
        }
    }

    @Test
    fun `stays inside the radius however coarsely it is sampled`() {
        // The previous implementation reflected off the boundary, which only
        // held while a step was shorter than two radii. Sampling once every ten
        // minutes in driving mode is the case that broke it.
        (0 until 500).forEach { i ->
            val sample = JitterEngine.sample(
                3.0, JitterEngine.Mode.DRIVING, start + i * 600_000L
            )
            assertTrue(
                "escaped: ${"%.3f".format(hypot(sample.dEastMeters, sample.dNorthMeters))}m",
                hypot(sample.dEastMeters, sample.dNorthMeters) <= 3.0 + 1e-9
            )
        }
    }

    @Test
    fun `every caller at the same instant gets the same point`() {
        // The property the whole design exists for: the module runs separately
        // inside every hooked app, and two apps asking where the device is at
        // the same moment must not get answers metres apart.
        val instant = start + 12_345
        val first = JitterEngine.sample(10.0, JitterEngine.Mode.WALKING, instant)
        val second = JitterEngine.sample(10.0, JitterEngine.Mode.WALKING, instant)
        assertEquals(first, second)
    }

    @Test
    fun `a caller joining late lands on the path, not at the centre`() {
        // Every app used to begin its walk at the exact centre of the circle,
        // so a freshly launched app announced itself by sitting on the anchor.
        val late = JitterEngine.sample(10.0, JitterEngine.Mode.WALKING, start + 3_600_000)
        assertTrue(hypot(late.dEastMeters, late.dNorthMeters) > 0.5)
    }

    @Test
    fun `starting at different moments starts at different points`() {
        // Pressing Start must not drop the fix on the anchor. The walk this
        // replaced accumulated its offset from zero, so every process — and so
        // every app, at every launch — began at the exact centre of the circle.
        val radius = 10.0
        val starts = (0 until 400).map { start + it * 7_919L }
        val offsets = starts.map { JitterEngine.sample(radius, JitterEngine.Mode.WALKING, it) }
            .map { hypot(it.dEastMeters, it.dNorthMeters) }

        assertTrue("start points are all alike", offsets.distinct().size > 350)
        // Spread through the circle rather than hugging either the centre or
        // the rim.
        val mean = offsets.average()
        assertTrue("mean start offset ${"%.2f".format(mean)}m", mean in 2.0..8.0)
        assertTrue("too many start on the anchor", offsets.count { it < 0.5 } < 20)
    }

    @Test
    fun `a radius of zero does not move`() {
        path(radius = 0.0, mode = JitterEngine.Mode.WALKING, steps = 100).forEach {
            assertEquals(0.0, it.dEastMeters, 0.0)
            assertEquals(0.0, it.dNorthMeters, 0.0)
            assertEquals(0f, it.speedMps, 0.0f)
        }
    }

    @Test
    fun `successive samples do not teleport`() {
        val stepMillis = 200L
        val dt = stepMillis / 1000.0
        var previous: JitterEngine.Sample? = null
        path(radius = 20.0, mode = JitterEngine.Mode.WALKING, stepMillis = stepMillis).forEach { s ->
            previous?.let {
                val moved = hypot(s.dEastMeters - it.dEastMeters, s.dNorthMeters - it.dNorthMeters)
                assertTrue("walked ${"%.2f".format(moved / dt)} m/s", moved / dt < 12.0)
            }
            previous = s
        }
    }

    @Test
    fun `speed is faster the faster the mode`() {
        fun meanSpeed(mode: JitterEngine.Mode) =
            path(radius = 30.0, mode = mode, steps = 2_000).map { it.speedMps }.average()

        val stationary = meanSpeed(JitterEngine.Mode.STATIONARY)
        val walking = meanSpeed(JitterEngine.Mode.WALKING)
        val driving = meanSpeed(JitterEngine.Mode.DRIVING)
        assertTrue("$stationary !< $walking", stationary < walking)
        assertTrue("$walking !< $driving", walking < driving)
    }

    @Test
    fun `bearing stays a compass value`() {
        path(radius = 15.0, mode = JitterEngine.Mode.DRIVING).forEach {
            assertTrue("bearing ${it.bearingDeg}", it.bearingDeg >= 0f && it.bearingDeg < 360f)
        }
    }

    @Test
    fun `the path does not repeat within an hour`() {
        // Three coprime periods per axis; a visibly cyclic track would be as
        // much of a tell as no movement at all.
        val first = JitterEngine.sample(10.0, JitterEngine.Mode.WALKING, start)
        val later = JitterEngine.sample(10.0, JitterEngine.Mode.WALKING, start + 3_600_000)
        assertNotEquals(first.dEastMeters, later.dEastMeters, 0.01)
    }

    @Test
    fun `the two axes are not the same curve`() {
        // Equal offsets on both axes would be a diagonal line, not a wander.
        val samples = path(radius = 10.0, mode = JitterEngine.Mode.WALKING, steps = 500)
        assertTrue(samples.any { kotlin.math.abs(it.dEastMeters - it.dNorthMeters) > 1.0 })
    }

    @Test
    fun `offset converts metres to degrees around the anchor`() {
        val (lat, lng) = JitterEngine.offset(39.9042, 116.4074, dEast = 0.0, dNorth = 111.32)
        assertEquals(39.9052, lat, 1e-4)
        assertEquals(116.4074, lng, 1e-9)
    }

    @Test
    fun `signal jitter stays within its spread`() {
        val random = Random(3)
        repeat(500) {
            val dbm = jitterDbm(base = -70, spreadDb = 3, random = random)
            assertTrue("$dbm", dbm in -73..-67)

            val cn0 = jitterCn0(base = 35f, spread = 1.5f, random = random)
            assertTrue("$cn0", cn0 in 33.5f..36.5f)
        }
    }

    @Test
    fun `carrier to noise never goes negative`() {
        val random = Random(5)
        repeat(500) {
            assertTrue(jitterCn0(base = 0.5f, spread = 4f, random = random) >= 0f)
        }
    }
}
