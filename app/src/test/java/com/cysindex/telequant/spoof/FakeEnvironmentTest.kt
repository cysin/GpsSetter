package com.cysindex.telequant.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The environment crosses a process boundary as JSON: the module app writes it,
 * the hook inside the target app reads it back. A field that does not survive
 * the trip becomes a signal reported as real when the recording never had it.
 */
class FakeEnvironmentTest {

    private val sample = FakeEnvironment(
        lat = 39.9042,
        lng = 116.4074,
        altitude = 43.5,
        accuracy = 7.5f,
        cells = listOf(
            CellRecord(
                type = "nr",
                mcc = 1, mnc = 1,
                lac = 54321,
                // Larger than Int.MAX_VALUE: NR's cell identity does not fit in
                // an Int, and narrowing it silently produces a different tower.
                cid = 9_876_543_210L,
                pci = 222, arfcn = 504990,
                dbm = -84, asu = 16, level = 2,
                registered = false,
                operatorLong = "TQ-TEST-NET", operatorShort = "TQTEST"
            )
        ),
        wifis = listOf(WifiRecord(bssid = "02:00:54:51:00:01", ssid = "TQ-TEST-01", level = -51)),
        beacons = listOf(BeaconRecord(address = "02:00:54:51:0B:01", name = "B1", rssi = -67)),
        satellites = listOf(
            SatelliteRecord(1, 7, 38.5f, 45f, 180f, true, true, true, 1575.42f)
        ),
        operatorName = "TQ-TEST-NET",
        operatorNumeric = "00101",
        countryIso = "zz",
        networkType = 20,
        timeZoneId = "Asia/Shanghai",
        recordedAt = 1_700_000_000_000L
    )

    @Test
    fun `survives a round trip unchanged`() {
        val back = FakeEnvironment.parse(sample.toJson().toString())
        assertEquals(sample, back)
    }

    @Test
    fun `a cell identity wider than an int survives`() {
        val back = FakeEnvironment.parse(sample.toJson().toString())!!
        assertEquals(9_876_543_210L, back.cells.single().cid)
    }

    @Test
    fun `absent optional fields stay absent`() {
        // Null and zero mean different things here: the hooks report a null as
        // CellInfo.UNAVAILABLE, while 0 is a legal identifier that reads as a
        // real measurement.
        val bare = CellRecord(type = "lte")
        val back = FakeEnvironment.parse(
            FakeEnvironment(lat = 1.0, lng = 2.0, cells = listOf(bare)).toJson().toString()
        )!!
        val cell = back.cells.single()
        assertNull(cell.mcc)
        assertNull(cell.lac)
        assertNull(cell.cid)
        assertNull(cell.pci)
    }

    @Test
    fun `an empty recording round trips`() {
        val empty = FakeEnvironment(lat = 0.0, lng = 0.0)
        assertEquals(empty, FakeEnvironment.parse(empty.toJson().toString()))
    }

    @Test
    fun `malformed input is not a crash`() {
        // This runs inside somebody else's app. Throwing here takes them down.
        assertNull(FakeEnvironment.parse(null))
        assertNull(FakeEnvironment.parse(""))
        assertNull(FakeEnvironment.parse("   "))
        assertNull(FakeEnvironment.parse("not json"))
        assertNull(FakeEnvironment.parse("[1,2,3]"))
    }

    @Test
    fun `null island counts as no fix`() {
        // A recording that timed out has lat=0,lng=0. Treating that as a real
        // position silently relocates whatever the user picked on the map.
        assertFalse(FakeEnvironment(lat = 0.0, lng = 0.0).hasFix())
        assertTrue(FakeEnvironment(lat = 39.9, lng = 116.4).hasFix())
        assertTrue(FakeEnvironment(lat = 0.0, lng = 116.4).hasFix())
        assertTrue(FakeEnvironment(lat = 39.9, lng = 0.0).hasFix())
    }
}
