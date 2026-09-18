package com.cysindex.telequant.spoof

/**
 * A synthetic environment whose values cannot be confused with the device's own.
 *
 * Verifying the radio hooks against a recording made on the same phone proves
 * nothing: the recording contains that phone's real towers, so a hooked read and
 * an unhooked one return the same thing. This builds an environment that is
 * unmistakably not real, so any probe showing these values has definitely gone
 * through the hooks.
 *
 * The values are fabricated but well-formed:
 *
 *  - MCC 001 / MNC 01 is the ITU-reserved test network. It belongs to no
 *    operator, so this impersonates nobody while still being a legal PLMN.
 *  - MAC addresses start with 0x02, the locally-administered bit, which is the
 *    range set aside for exactly this and cannot collide with a real vendor OUI.
 *  - Signal strengths, frequencies and ARFCNs sit in their normal ranges, so the
 *    data still looks like something a radio could report.
 */
object TestEnvironment {

    const val SSID_PREFIX = "TQ-TEST-"
    const val OPERATOR = "TQ-TEST-NET"
    const val TEST_MCC = 1
    const val TEST_MNC = 1

    /**
     * @param lat position to bind the fake radio data to — normally whatever the
     *   user has selected on the map, so position and surroundings still agree.
     */
    fun build(lat: Double, lng: Double): FakeEnvironment = FakeEnvironment(
        lat = lat,
        lng = lng,
        altitude = 43.0,
        accuracy = 8f,
        cells = listOf(
            CellRecord(
                type = "lte",
                mcc = TEST_MCC, mnc = TEST_MNC,
                lac = 12345, cid = 67890123L,
                pci = 111, arfcn = 1850,
                dbm = -76, asu = 22, level = 3,
                registered = true,
                operatorLong = OPERATOR, operatorShort = "TQTEST"
            ),
            CellRecord(
                type = "nr",
                mcc = TEST_MCC, mnc = TEST_MNC,
                lac = 54321, cid = 9876543210L,
                pci = 222, arfcn = 504990,
                dbm = -84, asu = 16, level = 2,
                registered = false,
                operatorLong = OPERATOR, operatorShort = "TQTEST"
            )
        ),
        wifis = (1..6).map { i ->
            WifiRecord(
                bssid = "02:00:54:51:00:%02X".format(i),
                ssid = "$SSID_PREFIX%02d".format(i),
                level = -45 - i * 6,
                frequency = if (i % 2 == 0) 5180 + i * 20 else 2412 + i * 5,
                capabilities = "[WPA2-PSK-CCMP][ESS]"
            )
        },
        beacons = (1..4).map { i ->
            BeaconRecord(
                address = "02:00:54:51:0B:%02X".format(i),
                name = "$SSID_PREFIX" + "BEACON-%02d".format(i),
                rssi = -62 - i * 5,
                txPower = -59
            )
        },
        satellites = emptyList(),
        operatorName = OPERATOR,
        operatorNumeric = "%03d%02d".format(TEST_MCC, TEST_MNC),
        countryIso = "zz",
        networkType = 20, // NR
        timeZoneId = null,
        recordedAt = System.currentTimeMillis()
    )
}
