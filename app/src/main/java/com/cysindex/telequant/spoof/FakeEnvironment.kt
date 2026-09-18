package com.cysindex.telequant.spoof

import org.json.JSONArray
import org.json.JSONObject

/**
 * A recorded (or synthesised) radio environment for one place.
 *
 * This model is shared by both processes: the module app writes it after a
 * recording, and the hooked process reads it back out of the world-readable
 * preferences. Serialisation is hand-rolled on [org.json] rather than
 * kotlinx.serialization so that neither side needs an extra compiler plugin —
 * the hook runs inside arbitrary target apps, where fewer moving parts is
 * worth more than convenience.
 *
 * Structured payloads that came off the wire (a BLE advertisement, a Wi-Fi
 * capabilities string) are stored verbatim. Reconstructing them from parsed
 * fields is how you end up emitting combinations the platform never produces.
 */
data class FakeEnvironment(
    val lat: Double,
    val lng: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 10f,
    val cells: List<CellRecord> = emptyList(),
    val wifis: List<WifiRecord> = emptyList(),
    val beacons: List<BeaconRecord> = emptyList(),
    val satellites: List<SatelliteRecord> = emptyList(),
    val operatorName: String? = null,
    val operatorNumeric: String? = null,
    val countryIso: String? = null,
    val networkType: Int = 0,
    val timeZoneId: String? = null,
    val recordedAt: Long = 0L
) {
    /**
     * Whether the recording actually obtained a position. Exactly 0,0 means the
     * GPS capture timed out — a real fix at Null Island is not a case worth
     * supporting, and treating the placeholder as real silently relocates the
     * user's chosen point.
     */
    fun hasFix(): Boolean = lat != 0.0 || lng != 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lng", lng)
        put("altitude", altitude)
        put("accuracy", accuracy.toDouble())
        put("cells", cells.map { it.toJson() }.toJsonArray())
        put("wifis", wifis.map { it.toJson() }.toJsonArray())
        put("beacons", beacons.map { it.toJson() }.toJsonArray())
        put("satellites", satellites.map { it.toJson() }.toJsonArray())
        putOpt("operatorName", operatorName)
        putOpt("operatorNumeric", operatorNumeric)
        putOpt("countryIso", countryIso)
        put("networkType", networkType)
        putOpt("timeZoneId", timeZoneId)
        put("recordedAt", recordedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = FakeEnvironment(
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            altitude = o.optDouble("altitude", 0.0),
            accuracy = o.optDouble("accuracy", 10.0).toFloat(),
            cells = o.optJSONArray("cells").mapObjects(CellRecord::fromJson),
            wifis = o.optJSONArray("wifis").mapObjects(WifiRecord::fromJson),
            beacons = o.optJSONArray("beacons").mapObjects(BeaconRecord::fromJson),
            satellites = o.optJSONArray("satellites").mapObjects(SatelliteRecord::fromJson),
            operatorName = o.optStringOrNull("operatorName"),
            operatorNumeric = o.optStringOrNull("operatorNumeric"),
            countryIso = o.optStringOrNull("countryIso"),
            networkType = o.optInt("networkType", 0),
            timeZoneId = o.optStringOrNull("timeZoneId"),
            recordedAt = o.optLong("recordedAt", 0L)
        )

        fun parse(json: String?): FakeEnvironment? = runCatching {
            if (json.isNullOrBlank()) null else fromJson(JSONObject(json))
        }.getOrNull()
    }
}

/**
 * One cell. [cid] is widened to Long because NR's NCI does not fit in an Int.
 * Absent fields stay null and are surfaced as [android.telephony.CellInfo.UNAVAILABLE]
 * rather than 0 — zero is a legal LAC/CID value and reads as "really is zero".
 */
data class CellRecord(
    val type: String,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val lac: Int? = null,
    val cid: Long? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val bsic: Int? = null,
    val psc: Int? = null,
    val dbm: Int = -95,
    val asu: Int = 16,
    val level: Int = 3,
    val registered: Boolean = true,
    val operatorLong: String? = null,
    val operatorShort: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        putOpt("mcc", mcc); putOpt("mnc", mnc)
        putOpt("lac", lac); putOpt("cid", cid)
        putOpt("pci", pci); putOpt("arfcn", arfcn)
        putOpt("bsic", bsic); putOpt("psc", psc)
        put("dbm", dbm); put("asu", asu); put("level", level)
        put("registered", registered)
        putOpt("operatorLong", operatorLong); putOpt("operatorShort", operatorShort)
    }

    companion object {
        fun fromJson(o: JSONObject) = CellRecord(
            type = o.optString("type", "lte"),
            mcc = o.optIntOrNull("mcc"), mnc = o.optIntOrNull("mnc"),
            lac = o.optIntOrNull("lac"), cid = o.optLongOrNull("cid"),
            pci = o.optIntOrNull("pci"), arfcn = o.optIntOrNull("arfcn"),
            bsic = o.optIntOrNull("bsic"), psc = o.optIntOrNull("psc"),
            dbm = o.optInt("dbm", -95), asu = o.optInt("asu", 16),
            level = o.optInt("level", 3),
            registered = o.optBoolean("registered", true),
            operatorLong = o.optStringOrNull("operatorLong"),
            operatorShort = o.optStringOrNull("operatorShort")
        )
    }
}

data class WifiRecord(
    val bssid: String,
    val ssid: String,
    val level: Int = -55,
    val frequency: Int = 2437,
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
    val channelWidth: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bssid", bssid); put("ssid", ssid)
        put("level", level); put("frequency", frequency)
        put("capabilities", capabilities); put("channelWidth", channelWidth)
    }

    companion object {
        fun fromJson(o: JSONObject) = WifiRecord(
            bssid = o.optString("bssid"),
            ssid = o.optString("ssid"),
            level = o.optInt("level", -55),
            frequency = o.optInt("frequency", 2437),
            capabilities = o.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"),
            channelWidth = o.optInt("channelWidth", 0)
        )
    }
}

/** [scanRecordHex] is the raw advertisement, kept byte-for-byte. */
data class BeaconRecord(
    val address: String,
    val name: String? = null,
    val rssi: Int = -75,
    val txPower: Int = -59,
    val scanRecordHex: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        putOpt("name", name)
        put("rssi", rssi); put("txPower", txPower)
        putOpt("scanRecordHex", scanRecordHex)
    }

    companion object {
        fun fromJson(o: JSONObject) = BeaconRecord(
            address = o.optString("address"),
            name = o.optStringOrNull("name"),
            rssi = o.optInt("rssi", -75),
            txPower = o.optInt("txPower", -59),
            scanRecordHex = o.optStringOrNull("scanRecordHex")
        )
    }
}

data class SatelliteRecord(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierFrequency: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("constellation", constellation); put("svid", svid)
        put("cn0", cn0.toDouble()); put("elevation", elevation.toDouble())
        put("azimuth", azimuth.toDouble())
        put("usedInFix", usedInFix)
        put("hasEphemeris", hasEphemeris); put("hasAlmanac", hasAlmanac)
        put("carrierFrequency", carrierFrequency.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = SatelliteRecord(
            constellation = o.optInt("constellation", 1),
            svid = o.optInt("svid", 1),
            cn0 = o.optDouble("cn0", 35.0).toFloat(),
            elevation = o.optDouble("elevation", 45.0).toFloat(),
            azimuth = o.optDouble("azimuth", 180.0).toFloat(),
            usedInFix = o.optBoolean("usedInFix", true),
            hasEphemeris = o.optBoolean("hasEphemeris", true),
            hasAlmanac = o.optBoolean("hasAlmanac", true),
            carrierFrequency = o.optDouble("carrierFrequency", 1575.42).toFloat()
        )
    }
}

// --- small org.json helpers -------------------------------------------------

private fun List<JSONObject>.toJsonArray(): JSONArray =
    JSONArray().also { arr -> forEach { arr.put(it) } }

private fun <T> JSONArray?.mapObjects(factory: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let(factory)
    }
}

/** [JSONObject.optString] turns a missing key into "", which is rarely wanted. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
