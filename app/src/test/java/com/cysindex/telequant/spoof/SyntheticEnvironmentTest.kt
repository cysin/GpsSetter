package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position-only mode. The properties that matter are not "looks random" but
 * "keeps the operator, invents the tower, and says the same thing tomorrow".
 */
class SyntheticEnvironmentTest {

    private fun build(lat: Double = 39.9042, lng: Double = 116.4074) =
        SyntheticEnvironment.build(
            lat = lat,
            lng = lng,
            operatorNumeric = "46000",
            operatorName = "中国移动",
            countryIso = "cn",
            networkType = 13
        )

    @Test
    fun `keeps the real operator`() {
        // The whole point of this mode over blanking: an app cross-checking the
        // cells against the SIM must find them consistent.
        val env = build()
        assertEquals("46000", env.operatorNumeric)
        env.cells.forEach {
            assertEquals(460, it.mcc)
            assertEquals(0, it.mnc)
            assertEquals("中国移动", it.operatorLong)
        }
    }

    @Test
    fun `fabricates the tower identifiers`() {
        val env = build()
        assertTrue("no cells produced", env.cells.isNotEmpty())
        env.cells.forEach {
            val cid = it.cid!!
            // Not zero: CID 0 with a real MCC is the blanking signature this
            // mode exists to avoid.
            assertTrue("cid $cid", cid in 1..268_435_455)
            assertTrue("lac ${it.lac}", it.lac!! in 1..65_533)
            assertTrue("pci ${it.pci}", it.pci!! in 0..503)
        }
    }

    @Test
    fun `the same place gives the same surroundings`() {
        // Towers that change identity on every launch are a giveaway on their
        // own, so this must be derived from the coordinate, not drawn fresh.
        val first = build()
        val second = build()
        assertEquals(first, second)
    }

    @Test
    fun `nearby jitter does not re-roll the neighbourhood`() {
        // Within a few metres it is the same street corner.
        val anchor = build(39.9042, 116.4074)
        val nudged = build(39.90421, 116.40741)
        assertEquals(anchor.cells, nudged.cells)
    }

    @Test
    fun `different places get different surroundings`() {
        assertNotEquals(build(39.9042, 116.4074).cells, build(31.2304, 121.4737).cells)
    }

    @Test
    fun `one serving cell, the rest neighbours`() {
        assertEquals(1, build().cells.count { it.registered })
    }

    @Test
    fun `neighbours share the tracking area and weaken with distance`() {
        val cells = build().cells
        assertEquals(1, cells.map { it.lac }.distinct().size)
        cells.zipWithNext().forEach { (nearer, further) ->
            assertTrue("signal did not fall off", further.dbm < nearer.dbm)
        }
        assertEquals(cells.size, cells.map { it.cid }.distinct().size)
    }

    @Test
    fun `access points are locally administered`() {
        // A fabricated vendor OUI could collide with a real access point and
        // resolve to a real place — the wrong one. This range cannot collide.
        build().wifis.forEach {
            val firstOctet = it.bssid.substringBefore(':').toInt(16)
            assertTrue("${it.bssid} not locally administered", firstOctet and 0x02 != 0)
            assertTrue("${it.bssid} is multicast", firstOctet and 0x01 == 0)
        }
    }

    @Test
    fun `no sim means no fabricated cells`() {
        // With no operator there is nothing to stay consistent with, so the
        // hooks are left to pass the real (empty) list through.
        val env = SyntheticEnvironment.build(
            lat = 39.9042, lng = 116.4074,
            operatorNumeric = null, operatorName = null, countryIso = null,
            networkType = 0
        )
        assertTrue(env.cells.isEmpty())
    }

    @Test
    fun `is marked as synthesised rather than recorded`() {
        // recordedAt distinguishes this from a real recording, which is how the
        // UI knows not to offer it back as a replayable environment.
        assertEquals(0L, build().recordedAt)
        assertTrue(build().hasFix())
    }
}
