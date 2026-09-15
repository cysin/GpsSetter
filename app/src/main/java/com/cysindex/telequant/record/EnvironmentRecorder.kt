package com.cysindex.telequant.record

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.cysindex.telequant.spoof.BeaconRecord
import com.cysindex.telequant.spoof.CellRecord
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.spoof.SatelliteRecord
import com.cysindex.telequant.spoof.WifiRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.TimeZone

/**
 * Captures the radio environment at the device's current position.
 *
 * The capture surface deliberately mirrors the hook surface one-for-one:
 * anything not recorded here has to be synthesised at replay time, and
 * synthesised data is what produces combinations the platform never emits.
 *
 * Raw payloads (a BLE advertisement, a Wi-Fi capabilities string) are kept
 * byte-for-byte rather than parsed and rebuilt, for the same reason.
 */
class EnvironmentRecorder(private val context: Context) {

    /** What could not be captured, and why — surfaced so the UI can say so. */
    data class Result(
        val environment: FakeEnvironment,
        val missing: List<String>
    )

    suspend fun record(): Result = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()

        coroutineScope {
            val locationJob = async { captureLocation(missing) }
            val gnssJob = async { captureSatellites() }
            val cellJob = async { captureCells(missing) }
            val wifiJob = async { captureWifi(missing) }
            val beaconJob = async { captureBeacons(missing) }

            awaitAll(locationJob, gnssJob, cellJob, wifiJob, beaconJob)

            val fix = locationJob.await()
            val cells = cellJob.await()
            val operator = captureOperator()

            Result(
                environment = FakeEnvironment(
                    lat = fix?.latitude ?: 0.0,
                    lng = fix?.longitude ?: 0.0,
                    altitude = fix?.altitude ?: 0.0,
                    accuracy = fix?.accuracy ?: 10f,
                    cells = cells,
                    wifis = wifiJob.await(),
                    beacons = beaconJob.await(),
                    satellites = gnssJob.await(),
                    operatorName = operator?.name,
                    operatorNumeric = operator?.numeric,
                    countryIso = operator?.countryIso,
                    networkType = operator?.networkType ?: 0,
                    timeZoneId = TimeZone.getDefault().id,
                    recordedAt = System.currentTimeMillis()
                ),
                missing = missing
            )
        }
    }

    // --- position -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun captureLocation(missing: MutableList<String>): android.location.Location? {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += "GPS (location permission)"
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                missing += "GPS (location services off)"
                return null
            }
        }

        val deferred = CompletableDeferred<android.location.Location?>()
        val signal = CancellationSignal()
        runCatching {
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                deferred.complete(location)
            }
        }.onFailure { return null }

        return withTimeoutOrNull(FIX_TIMEOUT_MS) { deferred.await() }
            ?: run {
                signal.cancel()
                missing += "GPS (no fix within ${FIX_TIMEOUT_MS / 1000}s)"
                null
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureSatellites(): List<SatelliteRecord> {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return emptyList()

        val deferred = CompletableDeferred<List<SatelliteRecord>>()
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (deferred.isCompleted) return
                deferred.complete(
                    (0 until status.satelliteCount).map { i ->
                        SatelliteRecord(
                            constellation = status.getConstellationType(i),
                            svid = status.getSvid(i),
                            cn0 = status.getCn0DbHz(i),
                            elevation = status.getElevationDegrees(i),
                            azimuth = status.getAzimuthDegrees(i),
                            usedInFix = status.usedInFix(i),
                            hasEphemeris = status.hasEphemerisData(i),
                            hasAlmanac = status.hasAlmanacData(i),
                            carrierFrequency = if (status.hasCarrierFrequencyHz(i)) {
                                status.getCarrierFrequencyHz(i) / 1_000_000f
                            } else 1575.42f
                        )
                    }
                )
            }
        }

        return runCatching {
            lm.registerGnssStatusCallback(context.mainExecutor, callback)
            withTimeoutOrNull(GNSS_TIMEOUT_MS) { deferred.await() }.orEmpty()
                .also { lm.unregisterGnssStatusCallback(callback) }
        }.getOrDefault(emptyList())
    }

    // --- cells --------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun captureCells(missing: MutableList<String>): List<CellRecord> {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += "cell towers (location permission)"
            return emptyList()
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()

        val infos = runCatching { tm.allCellInfo }.getOrNull()
        if (infos.isNullOrEmpty()) {
            missing += "cell towers (no service)"
            return emptyList()
        }
        return infos.mapNotNull { it.toRecord() }
    }

    private fun CellInfo.toRecord(): CellRecord? = runCatching {
        val dbm = cellSignalStrength.dbm
        val asu = cellSignalStrength.asuLevel
        val level = cellSignalStrength.level

        when (this) {
            is CellInfoLte -> (cellIdentity as CellIdentityLte).let { id ->
                CellRecord(
                    "lte", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                    id.tac.unavailableToNull(), id.ci.unavailableToNull()?.toLong(),
                    id.pci.unavailableToNull(), id.earfcn.unavailableToNull(),
                    null, null, dbm, asu, level, isRegistered,
                    id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()
                )
            }

            is CellInfoNr -> (cellIdentity as CellIdentityNr).let { id ->
                CellRecord(
                    "nr", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                    id.tac.unavailableToNull(), id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG },
                    id.pci.unavailableToNull(), id.nrarfcn.unavailableToNull(),
                    null, null, dbm, asu, level, isRegistered,
                    id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()
                )
            }

            is CellInfoGsm -> (cellIdentity as CellIdentityGsm).let { id ->
                CellRecord(
                    "gsm", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                    id.lac.unavailableToNull(), id.cid.unavailableToNull()?.toLong(),
                    null, id.arfcn.unavailableToNull(), id.bsic.unavailableToNull(), null,
                    dbm, asu, level, isRegistered,
                    id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()
                )
            }

            is CellInfoWcdma -> (cellIdentity as CellIdentityWcdma).let { id ->
                CellRecord(
                    "wcdma", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                    id.lac.unavailableToNull(), id.cid.unavailableToNull()?.toLong(),
                    null, id.uarfcn.unavailableToNull(), null, id.psc.unavailableToNull(),
                    dbm, asu, level, isRegistered,
                    id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()
                )
            }

            is CellInfoTdscdma -> (cellIdentity as CellIdentityTdscdma).let { id ->
                CellRecord(
                    "tdscdma", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                    id.lac.unavailableToNull(), id.cid.unavailableToNull()?.toLong(),
                    null, id.uarfcn.unavailableToNull(), null, id.cpid.unavailableToNull(),
                    dbm, asu, level, isRegistered,
                    id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()
                )
            }

            else -> null
        }
    }.onFailure { Timber.tag(TAG).d(it, "cell parse failed") }.getOrNull()

    private data class Operator(
        val name: String?,
        val numeric: String?,
        val countryIso: String?,
        val networkType: Int
    )

    @SuppressLint("MissingPermission")
    private fun captureOperator(): Operator? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching {
            Operator(
                name = tm.networkOperatorName?.takeIf { it.isNotEmpty() },
                numeric = tm.networkOperator?.takeIf { it.isNotEmpty() },
                countryIso = tm.networkCountryIso?.takeIf { it.isNotEmpty() },
                networkType = if (has(Manifest.permission.READ_PHONE_STATE)) {
                    runCatching { tm.dataNetworkType }.getOrDefault(0)
                } else 0
            )
        }.getOrNull()
    }

    // --- wi-fi --------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun captureWifi(missing: MutableList<String>): List<WifiRecord> {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!has(needed)) {
            missing += "Wi-Fi (nearby devices permission)"
            return emptyList()
        }

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()

        @Suppress("DEPRECATION")
        runCatching { wm.startScan() }
        delay(WIFI_SETTLE_MS)

        val results = runCatching { wm.scanResults }.getOrNull()
        if (results.isNullOrEmpty()) {
            missing += "Wi-Fi (no access points seen)"
            return emptyList()
        }

        return results.map { r ->
            // ScanResult.SSID is deprecated in favour of getWifiSsid(), but the
            // plain string is what every consumer of a WifiRecord needs.
            @Suppress("DEPRECATION")
            val ssid = r.SSID.orEmpty()
            WifiRecord(
                bssid = r.BSSID.orEmpty(),
                ssid = ssid,
                level = r.level,
                frequency = r.frequency,
                // Kept verbatim: rebuilding this string from parsed security
                // flags is how you produce capabilities no AP would advertise.
                capabilities = r.capabilities.orEmpty(),
                channelWidth = runCatching { r.channelWidth }.getOrDefault(0)
            )
        }
    }

    // --- bluetooth ----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun captureBeacons(missing: MutableList<String>): List<BeaconRecord> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !has(Manifest.permission.BLUETOOTH_SCAN)
        ) {
            missing += "Bluetooth (scan permission)"
            return emptyList()
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            missing += "Bluetooth (adapter off)"
            return emptyList()
        }

        val seen = LinkedHashMap<String, BeaconRecord>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                val r = result ?: return
                val address = runCatching { r.device.address }.getOrNull() ?: return
                seen.getOrPut(address) {
                    BeaconRecord(
                        address = address,
                        name = runCatching { r.device.name }.getOrNull(),
                        rssi = r.rssi,
                        txPower = runCatching { r.txPower }.getOrDefault(-59),
                        // The advertisement is stored as raw bytes and replayed
                        // through ScanRecord.parseFromBytes untouched.
                        scanRecordHex = r.scanRecord?.bytes?.toHex()
                    )
                }
            }
        }

        return runCatching {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            delay(BLE_SCAN_MS)
            scanner.stopScan(callback)
            if (seen.isEmpty()) missing += "Bluetooth (no beacons seen)"
            seen.values.toList()
        }.onFailure {
            missing += "Bluetooth (scan failed)"
        }.getOrDefault(emptyList())
    }

    // --- helpers ------------------------------------------------------------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The platform uses Integer.MAX_VALUE for "not available", not 0. */
    private fun Int.unavailableToNull(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    private companion object {
        const val TAG = "TeleQuantRec"
        const val FIX_TIMEOUT_MS = 12_000L
        const val GNSS_TIMEOUT_MS = 8_000L
        const val WIFI_SETTLE_MS = 3_000L
        const val BLE_SCAN_MS = 5_000L
    }
}
