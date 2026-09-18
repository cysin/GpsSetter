package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The jitter walk is the one piece of the module whose output is random, which
 * makes it the easiest place for a change to look fine and be wrong. Every test
 * here fixes the seed so a failure is reproducible.
 */
class JitterEngineTest {

    private val second = 1_000_000_000L

    private fun walk(
        radius: Double,
        mode: JitterEngine.Mode,
        steps: Int = 2_000,
        stepNanos: Long = second / 5,
        seed: Int = 42
    ): List<JitterEngine.Sample> {
        val engine = JitterEngine(radius, mode, Random(seed))
        var now = second
        return (0 until steps).map {
            now += stepNanos
            engine.sample(now)
        }
    }

    @Test
    fun `stays inside the radius`() {
        JitterEngine.Mode.entries.forEach { mode ->
            walk(radius = 10.0, mode = mode).forEachIndexed { i, sample ->
                val distance = hypot(sample.dEastMeters, sample.dNorthMeters)
                assertTrue(
                    "$mode escaped the radius at step $i: ${"%.2f".format(distance)}m > 10m",
                    distance <= 10.0 + 1e-6
                )
            }
        }
    }

    @Test
    fun `a radius of zero does not move`() {
        walk(radius = 0.0, mode = JitterEngine.Mode.WALKING).forEach {
            assertEquals(0.0, it.dEastMeters, 0.0)
            assertEquals(0.0, it.dNorthMeters, 0.0)
            assertEquals(0f, it.speedMps, 0.0f)
        }
    }

    @Test
    fun `the first sample sits on the anchor`() {
        // Until there is a previous sample there is no interval to move over,
        // and a fix that starts somewhere other than the chosen point is simply
        // the wrong place.
        val first = JitterEngine(10.0, JitterEngine.Mode.WALKING, Random(1)).sample(second)
        assertEquals(0.0, first.dEastMeters, 0.0)
        assertEquals(0.0, first.dNorthMeters, 0.0)
    }

    @Test
    fun `successive samples do not teleport`() {
        // The point of a correlated walk: consecutive positions have to be close
        // enough that the implied speed is physically possible. An independent
        // draw per call would pass every other test here and fail this one.
        val stepNanos = second / 5
        val dt = stepNanos / 1_000_000_000.0
        var previous: JitterEngine.Sample? = null
        walk(radius = 20.0, mode = JitterEngine.Mode.WALKING, stepNanos = stepNanos).forEach { s ->
            previous?.let {
                val moved = hypot(s.dEastMeters - it.dEastMeters, s.dNorthMeters - it.dNorthMeters)
                assertTrue("walked ${"%.2f".format(moved / dt)} m/s", moved / dt < 12.0)
            }
            previous = s
        }
    }

    @Test
    fun `speed matches the distance actually covered`() {
        val stepNanos = second / 5
        val dt = stepNanos / 1_000_000_000.0
        var previous: JitterEngine.Sample? = null
        walk(radius = 15.0, mode = JitterEngine.Mode.WALKING, stepNanos = stepNanos).forEach { s ->
            previous?.let {
                val moved = hypot(s.dEastMeters - it.dEastMeters, s.dNorthMeters - it.dNorthMeters)
                assertEquals((moved / dt).toFloat(), s.speedMps, 0.01f)
            }
            previous = s
        }
    }

    @Test
    fun `bearing stays a compass value`() {
        walk(radius = 15.0, mode = JitterEngine.Mode.DRIVING).forEach {
            assertTrue("bearing ${it.bearingDeg}", it.bearingDeg >= 0f && it.bearingDeg < 360f)
        }
    }

    @Test
    fun `a long gap does not produce one enormous step`() {
        // Waking up after ten minutes must not move the device ten minutes'
        // worth of wander in a single sample.
        val engine = JitterEngine(10.0, JitterEngine.Mode.DRIVING, Random(7))
        engine.sample(second)
        val after = engine.sample(second + 600 * second)
        assertTrue(hypot(after.dEastMeters, after.dNorthMeters) <= 10.0 + 1e-6)
    }

    @Test
    fun `a single large step cannot overshoot the boundary`() {
        // The escape hatch is a step longer than twice the radius: reflecting
        // such a step once lands it back outside. Driving mode over a five
        // second interval moves far enough for that to happen, so search for it
        // rather than trusting one seed.
        (1..400).forEach { seed ->
            val engine = JitterEngine(3.0, JitterEngine.Mode.DRIVING, Random(seed))
            var now = second
            engine.sample(now)
            repeat(20) {
                now += 5 * second
                val sample = engine.sample(now)
                val distance = hypot(sample.dEastMeters, sample.dNorthMeters)
                assertTrue(
                    "seed $seed escaped: ${"%.2f".format(distance)}m > 3m",
                    distance <= 3.0 + 1e-6
                )
            }
        }
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
