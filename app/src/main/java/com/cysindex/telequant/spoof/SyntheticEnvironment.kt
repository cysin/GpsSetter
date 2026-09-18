package com.cysindex.telequant.spoof

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A radio environment for a place nothing was ever recorded at — the "position
 * only" mode.
 *
 * The obvious implementations of that mode are both worse than this one:
 *
 *  - *Leave the radio alone.* The position says one city and the towers say
 *    another, and an app that locates off a tower database simply reads the real
 *    place. That defeats the whole exercise rather than limiting it.
 *  - *Blank it.* CID 0 with LAC 0 under a real MCC is a combination the platform
 *    does not emit (absent fields are `CellInfo.UNAVAILABLE`, and LAC 0 is
 *    reserved under 3GPP). It was the original code's approach and it is a
 *    fingerprint, not a disguise.
 *
 * So the operator stays real and only the tower identifiers are fabricated. A
 * lookup then fails to resolve — the identifiers are in nobody's database — and
 * the app falls back to GNSS, which this module already controls. What it looks
 * like from the outside is a tower of the subscriber's own carrier that the
 * database has not caught up with, which is an ordinary thing to encounter.
 *
 * Wi-Fi follows the same reasoning with locally-administered BSSIDs. A
 * fabricated vendor OUI risks colliding with a real access point somewhere,
 * which would resolve to a real place — the wrong one. An address in the
 * locally-administered range cannot collide, so the lookup fails cleanly.
 *
 * Everything is derived from the coordinate, so a place keeps the same
 * surroundings across restarts. Towers that change identity every time the app
 * is opened would be a giveaway on their own.
 */
object SyntheticEnvironment {

    /**
     * @param operatorNumeric the device's own MCC+MNC. With no SIM there is no
     *   operator to stay consistent with, so no cells are produced and the
     *   hooks leave the real (empty) list alone.
     */
    fun build(
        lat: Double,
        lng: Double,
        operatorNumeric: String?,
        operatorName: String?,
        countryIso: String?,
        networkType: Int
    ): FakeEnvironment {
        val random = Random(seedFor(lat, lng))
        val mcc = operatorNumeric?.take(3)?.toIntOrNull()
        val mnc = operatorNumeric?.drop(3)?.toIntOrNull()

        return FakeEnvironment(
            lat = lat,
            lng = lng,
            altitude = 0.0,
            accuracy = 12f,
            cells = if (mcc == null || mnc == null) emptyList() else buildCells(
                random, mcc, mnc, operatorName
            ),
            wifis = buildWifis(random),
            beacons = emptyList(), // Nothing nearby advertising is entirely normal.
            satellites = emptyList(),
            operatorName = operatorName,
            operatorNumeric = operatorNumeric,
            countryIso = countryIso,
            networkType = networkType,
            timeZoneId = null,
            recordedAt = 0L
        )
    }

    /**
     * Rounded to about 11 metres so that jitter, or nudging the marker by a few
     * pixels, does not re-roll the whole neighbourhood.
     */
    private fun seedFor(lat: Double, lng: Double): Int {
        val latKey = (lat * 10_000).roundToLong()
        val lngKey = (lng * 10_000).roundToLong()
        return (latKey * 73_856_093L xor lngKey * 19_349_663L).toInt()
    }

    private fun buildCells(
        random: Random,
        mcc: Int,
        mnc: Int,
        operatorName: String?
    ): List<CellRecord> {
        // One serving cell and two neighbours: a phone reporting exactly one
        // cell and nothing else is unusual outside a lab.
        val tac = random.nextInt(1, 65_534)
        return (0 until 3).map { index ->
            CellRecord(
                type = "lte",
                mcc = mcc,
                mnc = mnc,
                lac = tac, // Neighbours in one tracking area, as they would be.
                cid = random.nextInt(1, 268_435_455).toLong(),
                pci = random.nextInt(0, 504),
                arfcn = LTE_EARFCNS.random(random),
                dbm = -72 - index * 9,
                asu = 24 - index * 6,
                level = 4 - index,
                registered = index == 0,
                operatorLong = operatorName,
                operatorShort = operatorName?.take(8)
            )
        }
    }

    private fun buildWifis(random: Random): List<WifiRecord> =
        (0 until random.nextInt(3, 7)).map { index ->
            val mac = ByteArray(5) { random.nextInt(0, 256).toByte() }
            WifiRecord(
                // 0x02 is the locally-administered bit; the low bit stays clear
                // because a multicast address is not a valid BSSID.
                bssid = "02:" + mac.joinToString(":") { "%02X".format(it) },
                ssid = "WLAN-%04X".format(random.nextInt(0, 0x10000)),
                level = -48 - index * 7,
                frequency = if (index % 2 == 0) 2412 + (index % 11) * 5 else 5180 + index * 20,
                capabilities = "[WPA2-PSK-CCMP][ESS]"
            )
        }

    /** Band 3 and band 41 centre channels — the ones actually deployed. */
    private val LTE_EARFCNS = listOf(1650, 1725, 1800, 1850, 38950, 39148)
}
