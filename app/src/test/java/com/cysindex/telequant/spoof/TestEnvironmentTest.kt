package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthetic environment exists to prove a hook ran, so its values have to
 * be impossible to confuse with a real device's — and distinct from each other.
 *
 * That second part is not cosmetic. Three separate defects in this module were
 * "the first record gets used for everything": every cell reporting one tower,
 * every beacon reporting one name. Identical fixture values would have hidden
 * all of them, because the wrong answer and the right answer look the same.
 */
class TestEnvironmentTest {

    private val env = TestEnvironment.build(lat = 39.9042, lng = 116.4074)

    @Test
    fun `binds to the position it was given`() {
        assertEquals(39.9042, env.lat, 0.0)
        assertEquals(116.4074, env.lng, 0.0)
        assertTrue(env.hasFix())
    }

    @Test
    fun `every cell is distinguishable from every other`() {
        val cells = env.cells
        assertTrue("need at least two cells to tell them apart", cells.size >= 2)
        assertEquals("duplicate cell ids", cells.size, cells.map { it.cid }.distinct().size)
        assertEquals("duplicate pci", cells.size, cells.map { it.pci }.distinct().size)
        assertEquals("duplicate lac/tac", cells.size, cells.map { it.lac }.distinct().size)
    }

    @Test
    fun `cells cover more than one radio type`() {
        // A recording of one type cannot catch a record being stamped onto an
        // object of another.
        assertTrue(env.cells.map { it.type }.distinct().size >= 2)
    }

    @Test
    fun `every access point and beacon is distinguishable`() {
        assertEquals(env.wifis.size, env.wifis.map { it.bssid }.distinct().size)
        assertEquals(env.wifis.size, env.wifis.map { it.ssid }.distinct().size)
        assertEquals(env.beacons.size, env.beacons.map { it.address }.distinct().size)
        assertEquals(env.beacons.size, env.beacons.map { it.name }.distinct().size)
    }

    @Test
    fun `uses the reserved test network rather than a real operator`() {
        // MCC 001 / MNC 01 is the ITU test network: a legal PLMN belonging to
        // nobody, so this impersonates no carrier.
        assertEquals("00101", env.operatorNumeric)
        env.cells.forEach {
            assertEquals(1, it.mcc)
            assertEquals(1, it.mnc)
        }
    }

    @Test
    fun `mac addresses are locally administered`() {
        // Bit 1 of the first octet marks a locally-administered address, the
        // range set aside for exactly this. It cannot collide with a real
        // vendor's OUI.
        (env.wifis.map { it.bssid } + env.beacons.map { it.address }).forEach { mac ->
            val firstOctet = mac.substringBefore(':').toInt(16)
            assertTrue("$mac is not locally administered", firstOctet and 0x02 != 0)
            assertTrue("$mac is a multicast address", firstOctet and 0x01 == 0)
        }
    }

    @Test
    fun `radio values stay in ranges a radio could report`() {
        env.cells.forEach {
            assertTrue("dbm ${it.dbm}", it.dbm in -140..-40)
            assertTrue("level ${it.level}", it.level in 0..4)
        }
        env.wifis.forEach {
            assertTrue("level ${it.level}", it.level in -100..-20)
            assertTrue("frequency ${it.frequency}", it.frequency in 2400..5900)
        }
        env.beacons.forEach {
            assertTrue("rssi ${it.rssi}", it.rssi in -110..-20)
        }
    }

    @Test
    fun `exactly one cell is the serving cell`() {
        // Two registered cells is a state the radio does not report.
        assertEquals(1, env.cells.count { it.registered })
    }
}
