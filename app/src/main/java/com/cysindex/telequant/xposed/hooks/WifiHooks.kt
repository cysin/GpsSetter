package com.cysindex.telequant.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import com.cysindex.telequant.spoof.WifiRecord
import com.cysindex.telequant.xposed.core.CellFactory
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.ListClass
import com.highcapable.yukihookapi.hook.type.java.StringClass
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Tier B: Wi-Fi, the signal most "GPS-free" positioning actually runs on.
 *
 * The previous implementation blanked everything — `getScanResults` returned an
 * empty list and SSID/BSSID became "". That defeats positioning but is itself a
 * tell: the platform reports `"<unknown ssid>"` when the SSID is unknown and
 * `02:00:00:00:00:00` for a BSSID the caller lacks permission to see. An empty
 * string is a value the framework never produces.
 *
 * These hooks serve the recorded access points instead, so an app that locates
 * by Wi-Fi resolves to the chosen place rather than failing over to something
 * else.
 */
object WifiHooks : YukiBaseHooker() {

    /**
     * Captured once: inside `toClass().apply { }` the receiver is Class<*>, so
     * a bare `packageName` there resolves to Class.getPackageName() rather than
     * the hooked app's package — wrong value, and API 31+ besides.
     */
    private var hostPackage: String = ""

    override fun onHook() {
        hostPackage = packageName
        hookScanResults()
        hookWifiInfo()
        hookTransportInfo()
        hookRtt()
    }

    private fun snapshot(): SpoofEngine.Snapshot? =
        SpoofEngine.current().takeIf { it.enabled }

    private fun hookScanResults() {
        val wm = "android.net.wifi.WifiManager".toClass()

        wm.apply {
            if (hasMethod { name = "getScanResults"; emptyParam(); returnType = ListClass }) {
                method { name = "getScanResults"; emptyParam(); returnType = ListClass }.hook {
                    after {
                        val snapshot = snapshot() ?: return@after
                        if (!snapshot.hasWifis) return@after
                        @Suppress("UNCHECKED_CAST")
                        val real = result as? List<ScanResult> ?: return@after
                        result = buildScanResults(real, snapshot.wifis)
                    }
                }
            }

            // Returning false here makes apps think Wi-Fi scanning is broken and
            // some then fall back to another signal, so report success.
            if (hasMethod { name = "startScan"; emptyParam(); returnType = BooleanType }) {
                method { name = "startScan"; emptyParam(); returnType = BooleanType }.hook {
                    before { if (snapshot() != null) result = true }
                }
            }

            if (hasMethod { name = "getConfiguredNetworks" }) {
                method { name = "getConfiguredNetworks" }.hookAll {
                    after { if (snapshot() != null) result = emptyList<Any>() }
                }
            }
        }
    }

    /**
     * Clones real [ScanResult] objects and overwrites their fields rather than
     * constructing new ones: the public constructors are hidden and their
     * signatures move between API levels, so a copy keeps whatever the platform
     * put in the fields this does not set.
     *
     * With nothing to clone — Wi-Fi switched off, or genuinely no access point
     * in range — one is constructed instead. Returning the real (empty) list
     * there used to mean the recording was invisible in exactly the situation
     * where an app has no other access points to fall back on.
     */
    private fun buildScanResults(
        real: List<ScanResult>,
        records: List<WifiRecord>
    ): List<ScanResult> {
        val template = real.firstOrNull()
        val out = ArrayList<ScanResult>(records.size)
        records.forEach { record ->
            val copy = template?.let { runCatching { ScanResult(it) }.getOrNull() }
                ?: construct()
                ?: return@forEach
            runCatching {
                copy.BSSID = record.bssid
                @Suppress("DEPRECATION")
                copy.SSID = record.ssid
                copy.level = record.level
                copy.frequency = record.frequency
                copy.capabilities = record.capabilities
                copy.timestamp = SystemClockMicros()
            }.onFailure { YLog.warn("ScanResult rewrite failed: $it") }
            out += copy
        }
        return if (out.isEmpty()) real else out
    }

    /**
     * A blank [ScanResult] through the constructor the platform hides. Every
     * field this module cares about is public on the class, so the object only
     * has to exist.
     */
    private fun construct(): ScanResult? = runCatching {
        CellFactory.ensureExemptions()
        val clazz = ScanResult::class.java
        runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: HiddenApiBypass.newInstance(clazz) as? ScanResult
    }.onFailure { YLog.warn("ScanResult construction failed: $it") }.getOrNull()

    /** ScanResult.timestamp is microseconds since boot, not epoch millis. */
    private fun SystemClockMicros(): Long = android.os.SystemClock.elapsedRealtime() * 1000

    private fun hookWifiInfo() {
        val info = "android.net.wifi.WifiInfo".toClass()
        info.apply {
            hookString("getBSSID") { it.bssid }
            // The platform wraps the SSID in quotes here.
            hookString("getSSID") { "\"${it.ssid}\"" }
            hookInt("getRssi") { it.level }
            hookInt("getFrequency") { it.frequency }

            // API 33+. Not covered before, so a modern app reading the SSID
            // through this accessor saw the real network.
            if (hasMethod { name = "getWifiSsid" }) {
                method { name = "getWifiSsid" }.hookAll {
                    after {
                        val record = primaryWifi() ?: return@after
                        val ssidClass = "android.net.wifi.WifiSsid"
                        if (!ssidClass.hasClass()) return@after
                        runCatching {
                            result = ssidClass.toClass()
                                .getMethod("fromBytes", ByteArray::class.java)
                                .invoke(null, record.ssid.toByteArray())
                        }
                    }
                }
            }
        }
    }

    /**
     * API 31+ path: apps read Wi-Fi state from
     * `NetworkCapabilities.getTransportInfo()`, which returns a [WifiInfo] and
     * bypasses `WifiManager.getConnectionInfo()` entirely. The old hooks did not
     * touch this at all, so the modern way of asking returned the real network.
     */
    private fun hookTransportInfo() {
        val className = "android.net.NetworkCapabilities"
        if (!className.hasClass()) return
        className.toClass().apply {
            if (!hasMethod { name = "getTransportInfo" }) return@apply
            method { name = "getTransportInfo" }.hookAll {
                after {
                    // The returned WifiInfo is covered by the accessor hooks
                    // above; this exists so the path is explicitly accounted
                    // for rather than relied upon by accident.
                    val snapshot = snapshot() ?: return@after
                    if (result is WifiInfo && snapshot.hasWifis) {
                        YLog.debug("[$hostPackage] transportInfo served spoofed WifiInfo")
                    }
                }
            }
        }
    }

    /**
     * 802.11mc ranging measures the distance to known access points and
     * trilaterates from it — a positioning channel entirely separate from scan
     * results. Suppressed rather than faked: inventing self-consistent
     * round-trip times is not realistic.
     */
    private fun hookRtt() {
        val className = "android.net.wifi.rtt.WifiRttManager"
        if (!className.hasClass()) return
        className.toClass().apply {
            if (!hasMethod { name = "startRanging" }) return@apply
            method { name = "startRanging" }.hookAll {
                before {
                    if (snapshot() != null) {
                        result = null
                        YLog.debug("[$hostPackage] suppressed WiFi RTT ranging")
                    }
                }
            }
        }
    }

    /** Null unless Wi-Fi spoofing is on and an access point was recorded. */
    private fun primaryWifi(): WifiRecord? =
        snapshot()?.takeIf { it.hasWifis }?.wifis?.firstOrNull()

    private fun Class<*>.hookString(methodName: String, pick: (WifiRecord) -> String?) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = StringClass }) return
        method { name = methodName; emptyParam(); returnType = StringClass }.hook {
            before { primaryWifi()?.let { r -> pick(r)?.let { value -> result = value } } }
        }
    }

    private fun Class<*>.hookInt(methodName: String, pick: (WifiRecord) -> Int) {
        if (!hasMethod { name = methodName; emptyParam(); returnType = IntType }) return
        method { name = methodName; emptyParam(); returnType = IntType }.hook {
            before { primaryWifi()?.let { r -> result = pick(r) } }
        }
    }
}
