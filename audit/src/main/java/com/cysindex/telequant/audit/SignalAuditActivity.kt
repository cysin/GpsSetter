package com.cysindex.telequant.audit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Calls every path the module claims to cover and prints what came back.
 *
 * Checking a hook surface against a written list by hand does not scale and
 * quietly goes stale; this reads the live values instead, so a path that still
 * returns the device's real data is visible at a glance. Add this app to the
 * module's own scope and open it.
 *
 * Debug variant only — it exists to audit the module, not to ship.
 */
class SignalAuditActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        // Without these every probe reports SKIPPED, which looks like the hooks
        // failing rather than the auditor never having asked.
        requestPermissions(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray(),
            1
        )

        setContentView(ScrollView(this).apply {
            addView(
                container,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        audit()
    }

    /** Re-runs the probes once the user has answered the permission dialog. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        container.removeAllViews()
        audit()
    }

    private fun audit() {
        section("A — Position")
        auditLocation()

        section("B — Cell")
        auditCell()

        section("B — Wi-Fi")
        auditWifi()

        section("B — Bluetooth")
        auditBluetooth()

        section("C — Consistency")
        auditConsistency()
    }

    @SuppressLint("MissingPermission")
    private fun auditLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            row("location", "SKIPPED — no permission")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            row(
                "getLastKnownLocation($provider)",
                loc?.let {
                    "%.6f, %.6f  acc=%.1f  ert=%d  spd=%.2f"
                        .format(it.latitude, it.longitude, it.accuracy, it.elapsedRealtimeNanos, it.speed)
                } ?: "null"
            )
            // Read the fields through toString() too: the getter and the field
            // used to disagree, which is exactly what this is here to catch.
            loc?.let { row("  └ toString()", it.toString()) }
        }

        row("isProviderEnabled(gps)", lm.isProviderEnabled(LocationManager.GPS_PROVIDER).toString())
        row("getProviders(true)", runCatching { lm.getProviders(true).toString() }.getOrDefault("?"))
        row("isLocationEnabled", runCatching { lm.isLocationEnabled.toString() }.getOrDefault("?"))
    }

    @SuppressLint("MissingPermission")
    private fun auditCell() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        row("getNetworkOperator", tm.networkOperator.orEmpty().ifEmpty { "(empty)" })
        row("getNetworkOperatorName", tm.networkOperatorName.orEmpty().ifEmpty { "(empty)" })
        row("getSimOperator", tm.simOperator.orEmpty().ifEmpty { "(empty)" })
        row("getNetworkCountryIso", tm.networkCountryIso.orEmpty().ifEmpty { "(empty)" })
        row("getSimCountryIso", tm.simCountryIso.orEmpty().ifEmpty { "(empty)" })

        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            row("getAllCellInfo", "SKIPPED — no permission")
            return
        }
        val infos = runCatching { tm.allCellInfo }.getOrNull()
        if (infos.isNullOrEmpty()) {
            row("getAllCellInfo", "empty (no service, or nothing to rewrite)")
            return
        }
        infos.take(4).forEach { info -> row("cell", describeCell(info)) }
    }

    private fun describeCell(info: CellInfo): String = runCatching {
        when (info) {
            is CellInfoLte -> info.cellIdentity.let {
                "LTE mcc=${it.mccString} mnc=${it.mncString} tac=${fmt(it.tac)} " +
                        "ci=${fmt(it.ci)} pci=${fmt(it.pci)} dbm=${info.cellSignalStrength.dbm}"
            }

            is CellInfoNr -> "NR ${info.cellIdentity} dbm=${info.cellSignalStrength.dbm}"
            is CellInfoGsm -> info.cellIdentity.let {
                "GSM mcc=${it.mccString} mnc=${it.mncString} lac=${fmt(it.lac)} cid=${fmt(it.cid)}"
            }

            else -> info.toString()
        }
    }.getOrDefault("?")

    /** Makes the "unavailable" sentinel obvious instead of printing 2147483647. */
    private fun fmt(v: Int) = if (v == CellInfo.UNAVAILABLE) "UNAVAILABLE" else v.toString()

    @SuppressLint("MissingPermission")
    private fun auditWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull()
        row("WifiInfo.getBSSID", info?.bssid ?: "null")
        @Suppress("DEPRECATION")
        row("WifiInfo.getSSID", info?.ssid ?: "null")
        row("WifiInfo.getRssi", info?.rssi?.toString() ?: "null")

        // The API 31+ path that bypasses WifiManager entirely.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val transport = runCatching { caps?.transportInfo }.getOrNull()
        row(
            "NetworkCapabilities.getTransportInfo",
            when {
                transport is WifiInfo -> "WifiInfo bssid=${transport.bssid}"
                transport == null -> "null"
                else -> transport.javaClass.simpleName
            }
        )

        val scans = runCatching { wm.scanResults }.getOrNull()
        row("getScanResults", "${scans?.size ?: 0} results")
        scans?.take(4)?.forEach {
            @Suppress("DEPRECATION")
            row("  └ ap", "${it.SSID} ${it.BSSID} ${it.level}dBm ${it.frequency}MHz")
        }
    }

    @SuppressLint("MissingPermission")
    private fun auditBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            row("bluetooth", "SKIPPED — no permission")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        row("adapter", if (adapter == null) "null" else "enabled=${adapter.isEnabled}")
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull()
        row("getBondedDevices", "${bonded?.size ?: 0} devices")
        row("LE scan", "start one from a scanner app and watch logcat for spoofed results")
    }

    private fun auditConsistency() {
        row("TimeZone.getDefault", TimeZone.getDefault().id)
        row("Locale country", resources.configuration.locales[0].country)
        row(
            "Settings mock_location",
            runCatching {
                android.provider.Settings.Secure.getString(contentResolver, "mock_location")
            }.getOrNull() ?: "null"
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })
    }

    private fun row(label: String, value: String) {
        container.addView(TextView(this).apply {
            text = "$label\n    $value"
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 4, 0, 4)
        })
    }
}
