package com.cysindex.telequant.xposed.hooks

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.cysindex.telequant.spoof.BeaconRecord
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.StringClass

/**
 * Tier B: Bluetooth. This had no coverage at all before, so beacon-based
 * positioning (iBeacon/Eddystone, indoor navigation, proximity check-in) saw
 * the device's genuine surroundings no matter what the GPS hooks reported.
 *
 * Beacons are rebuilt from the recorded advertisement bytes rather than from
 * parsed fields — reconstructing an advertisement from its interpretation is
 * how you emit frames no real beacon would send.
 */
object BluetoothHooks : YukiBaseHooker() {

    override fun onHook() {
        hookLeScanner()
        hookDiscovery()
        hookFoundBroadcast()
        hookDeviceIdentity()
    }

    private fun snapshot(): SpoofEngine.Snapshot? =
        SpoofEngine.current().takeIf { it.enabled }

    /**
     * Wraps the app's [ScanCallback] so results are replaced with the recorded
     * beacons, delivered on whatever thread the framework already uses for it.
     */
    private fun hookLeScanner() {
        val className = "android.bluetooth.le.BluetoothLeScanner"
        if (!className.hasClass()) return

        className.toClass().apply {
            if (!hasMethod { name = "startScan" }) return@apply
            method { name = "startScan" }.hookAll {
                before {
                    val snapshot = snapshot() ?: return@before
                    if (!snapshot.hasBeacons) return@before

                    val index = args.indexOfFirst { it is ScanCallback }
                    if (index < 0) return@before
                    val original = args[index] as ScanCallback
                    args[index] = SpoofingScanCallback(original)
                }
            }
        }
    }

    private class SpoofingScanCallback(
        private val delegate: ScanCallback
    ) : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val snapshot = SpoofEngine.current()
            if (!snapshot.enabled || !snapshot.hasBeacons) {
                delegate.onScanResult(callbackType, result)
                return
            }
            // Use the real result as a template so every field we do not
            // override keeps a shape the platform actually produces.
            val template = result ?: return
            snapshot.beacons.forEach { beacon ->
                buildResult(template, beacon)?.let { delegate.onScanResult(callbackType, it) }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val snapshot = SpoofEngine.current()
            if (!snapshot.enabled || !snapshot.hasBeacons) {
                delegate.onBatchScanResults(results)
                return
            }
            val template = results?.firstOrNull() ?: return
            val spoofed = snapshot.beacons.mapNotNull { buildResult(template, it) }
            delegate.onBatchScanResults(spoofed.toMutableList())
        }

        override fun onScanFailed(errorCode: Int) = delegate.onScanFailed(errorCode)

        private fun buildResult(template: ScanResult, beacon: BeaconRecord): ScanResult? =
            runCatching {
                // getRemoteDevice is public API and accepts an arbitrary MAC.
                val device: BluetoothDevice = BluetoothAdapter.getDefaultAdapter()
                    .getRemoteDevice(beacon.address)

                val record = beacon.scanRecordHex
                    ?.let { hex -> parseScanRecord(hex.hexToBytes()) }
                    ?: template.scanRecord

                // The legacy four-argument constructor, rather than the extended
                // one: that needs an eventType, which ScanResult exposes no
                // public getter for, so it could only be guessed. This form lets
                // the framework pick the legacy-advertisement value itself.
                ScanResult(
                    device,
                    record,
                    beacon.rssi,
                    SystemClock.elapsedRealtimeNanos()
                )
            }.onFailure { YLog.warn("beacon build failed: $it") }.getOrNull()

        /** ScanRecord.parseFromBytes is hidden but stable across versions. */
        private fun parseScanRecord(bytes: ByteArray): android.bluetooth.le.ScanRecord? =
            runCatching {
                android.bluetooth.le.ScanRecord::class.java
                    .getMethod("parseFromBytes", ByteArray::class.java)
                    .invoke(null, bytes) as? android.bluetooth.le.ScanRecord
            }.getOrNull()
    }

    private fun hookDiscovery() {
        val className = "android.bluetooth.BluetoothAdapter"
        if (!className.hasClass()) return
        className.toClass().apply {
            if (hasMethod { name = "startDiscovery"; emptyParam(); returnType = BooleanType }) {
                method { name = "startDiscovery"; emptyParam(); returnType = BooleanType }.hook {
                    before {
                        // Classic discovery leaks the surrounding devices via
                        // ACTION_FOUND, which is rewritten below; report success
                        // so the app does not treat Bluetooth as unavailable.
                        if (snapshot()?.hasBeacons == true) result = true
                    }
                }
            }
            if (hasMethod { name = "getBondedDevices" }) {
                method { name = "getBondedDevices" }.hookAll {
                    after { if (snapshot() != null) result = emptySet<BluetoothDevice>() }
                }
            }
        }
    }

    /**
     * Rewrites the ACTION_FOUND broadcast, which carries the discovered device
     * and its RSSI in the intent itself — unlike Wi-Fi, where the broadcast is
     * only a trigger and the data is fetched afterwards through an API that is
     * already hooked.
     *
     * `BroadcastReceiver.onReceive` cannot be hooked directly: it is an abstract
     * method on an abstract class, so there is no body to replace and LSPlant
     * rejects it ("Try to hook ... got an exception"). Instead each receiver is
     * caught as it registers and its concrete subclass is hooked, which does
     * have an implementation.
     */
    private fun hookFoundBroadcast() {
        "android.content.ContextWrapper".toClass().apply {
            if (!hasMethod { name = "registerReceiver" }) return@apply
            method { name = "registerReceiver" }.hookAll {
                before {
                    val receiver = args.firstOrNull { it is BroadcastReceiver } ?: return@before
                    val filter = args.firstOrNull { it is IntentFilter } as? IntentFilter
                    // Only receivers that actually asked for device discovery.
                    if (filter?.hasAction(BluetoothDevice.ACTION_FOUND) != true) return@before
                    hookReceiverClass(receiver.javaClass)
                }
            }
        }
    }

    /** Hooks one concrete receiver class, once. */
    private fun hookReceiverClass(clazz: Class<*>) {
        if (!hookedReceivers.add(clazz.name)) return
        runCatching {
            clazz.method { name = "onReceive" }.hookAll {
                before {
                    val snapshot = snapshot() ?: return@before
                    if (!snapshot.hasBeacons) return@before
                    val intent = args.firstOrNull { it is Intent } as? Intent ?: return@before
                    if (intent.action != BluetoothDevice.ACTION_FOUND) return@before

                    val beacon = snapshot.beacons.firstOrNull() ?: return@before
                    runCatching {
                        val device = BluetoothAdapter.getDefaultAdapter()
                            .getRemoteDevice(beacon.address)
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                        intent.putExtra(BluetoothDevice.EXTRA_RSSI, beacon.rssi.toShort())
                        beacon.name?.let { intent.putExtra(BluetoothDevice.EXTRA_NAME, it) }
                    }
                }
            }
        }.onFailure { YLog.debug("receiver hook skipped for ${clazz.name}: $it") }
    }

    private val hookedReceivers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun hookDeviceIdentity() {

        // Device identity, for code holding a BluetoothDevice directly.
        "android.bluetooth.BluetoothDevice".toClass().apply {
            if (hasMethod { name = "getName"; emptyParam(); returnType = StringClass }) {
                method { name = "getName"; emptyParam(); returnType = StringClass }.hook {
                    before {
                        val beacon = snapshot()?.beacons?.firstOrNull() ?: return@before
                        beacon.name?.let { result = it }
                    }
                }
            }
        }
    }
}

private fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { i ->
        ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
    }
}
