package com.cysindex.telequant.xposed.hooks

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

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
        hookScanBroadcast()
        hookDeviceIdentity()
    }

    private fun snapshot(): SpoofEngine.Snapshot? =
        SpoofEngine.current().takeIf { it.enabled }

    /**
     * Swaps the app's [ScanCallback] for one that hides the genuine devices and
     * reports the recorded beacons instead.
     */
    private fun hookLeScanner() {
        val className = "android.bluetooth.le.BluetoothLeScanner"
        if (!className.hasClass()) return

        className.toClass().apply {
            if (hasMethod { name = "startScan" }) {
                method { name = "startScan" }.hookAll {
                    before {
                        val snapshot = snapshot() ?: return@before
                        if (!snapshot.hasBeacons) return@before

                        val index = args.indexOfFirst { it is ScanCallback }
                        if (index < 0) return@before
                        args[index] = BeaconEmitter.attach(args[index] as ScanCallback)
                    }
                }
            }
            // The framework keys its scan registry on the object startScan was
            // given, which is the wrapper — so an app stopping its own callback
            // matches nothing and the scan runs until the process dies. Translate
            // the argument back to whatever was substituted for it.
            if (hasMethod { name = "stopScan" }) {
                method { name = "stopScan" }.hookAll {
                    before {
                        val index = args.indexOfFirst { it is ScanCallback }
                        if (index < 0) return@before
                        val wrapper = BeaconEmitter.detach(args[index] as ScanCallback)
                        if (wrapper != null) args[index] = wrapper
                    }
                }
            }
        }
    }

    /**
     * Drives beacon delivery on a timer instead of rewriting genuine results as
     * they arrive.
     *
     * Rewriting was not enough: a result only arrives when something real is
     * advertising nearby, so an app scanning in a quiet room saw nothing at all
     * and concluded there were no beacons — the recording was invisible exactly
     * where beacon positioning matters most. The emitter is now the only source
     * and genuine results are dropped.
     *
     * Delivery is posted to the main looper because that is where the platform's
     * own scan callbacks land.
     */
    private object BeaconEmitter {

        private const val INTERVAL_MS = 1_000L

        private val handler = Handler(Looper.getMainLooper())

        /**
         * Weak on both sides. The key is the app's callback, which this module
         * must not keep alive; the value would pin it through an ordinary strong
         * field, so the wrapper holds it weakly too and the scan stops on its own
         * once the app has let go. The framework holds the wrapper for as long as
         * the scan is registered.
         */
        private val wrappers =
            Collections.synchronizedMap(WeakHashMap<ScanCallback, Wrapper>())

        fun attach(original: ScanCallback): ScanCallback =
            synchronized(wrappers) {
                wrappers.getOrPut(original) { Wrapper(original) }
            }.also { it.start() }

        fun detach(original: ScanCallback): ScanCallback? =
            synchronized(wrappers) { wrappers.remove(original) }?.also { it.stop() }

        private class Wrapper(delegate: ScanCallback) : ScanCallback() {

            private val delegateRef = WeakReference(delegate)

            @Volatile
            private var running = false

            private val tick = object : Runnable {
                override fun run() {
                    val target = delegateRef.get()
                    if (target == null) {
                        stop()
                        return
                    }
                    val snapshot = SpoofEngine.current()
                    if (snapshot.enabled && snapshot.hasBeacons) {
                        snapshot.beacons.forEach { beacon ->
                            build(beacon)?.let {
                                target.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it)
                            }
                        }
                    }
                    handler.postDelayed(this, INTERVAL_MS)
                }
            }

            fun start() {
                if (running) return
                running = true
                handler.post(tick)
            }

            fun stop() {
                running = false
                handler.removeCallbacks(tick)
            }

            // Genuine results are suppressed while spoofing: one real device in
            // a list that is otherwise the recording contradicts every other
            // signal the module reports.
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (!spoofing()) delegateRef.get()?.onScanResult(callbackType, result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                if (!spoofing()) delegateRef.get()?.onBatchScanResults(results)
            }

            override fun onScanFailed(errorCode: Int) {
                delegateRef.get()?.onScanFailed(errorCode)
            }

            private fun spoofing(): Boolean =
                SpoofEngine.current().let { it.enabled && it.hasBeacons }
        }

        fun build(beacon: BeaconRecord): ScanResult? =
            runCatching {
                // getRemoteDevice is public API and accepts an arbitrary MAC.
                val device: BluetoothDevice = BluetoothAdapter.getDefaultAdapter()
                    .getRemoteDevice(beacon.address)

                val bytes = beacon.scanRecordHex?.hexToBytes() ?: synthesiseAdvertisement(beacon)

                // The legacy four-argument constructor, rather than the extended
                // one: that needs an eventType, which ScanResult exposes no
                // public getter for, so it could only be guessed. This form lets
                // the framework pick the legacy-advertisement value itself.
                ScanResult(
                    device,
                    parseScanRecord(bytes),
                    beacon.rssi,
                    SystemClock.elapsedRealtimeNanos()
                )
            }.onFailure { YLog.warn("beacon build failed: $it") }.getOrNull()

        /**
         * A minimal advertisement for a beacon that was never recorded off the
         * air — a synthesised profile, say. Recorded beacons keep their original
         * bytes; this is only the fallback, and it carries nothing but flags, TX
         * power and the name so that it stays a frame a real device could send.
         */
        private fun synthesiseAdvertisement(beacon: BeaconRecord): ByteArray {
            val out = ArrayList<Byte>(MAX_ADVERTISEMENT)
            // Flags: LE General Discoverable, BR/EDR not supported.
            out.add(2); out.add(0x01); out.add(0x06)
            out.add(2); out.add(0x0A); out.add(beacon.txPower.toByte())
            beacon.name?.encodeToByteArray()
                ?.take(MAX_ADVERTISEMENT - out.size - 2)
                ?.takeIf { it.isNotEmpty() }
                ?.let { name ->
                    out.add((name.size + 1).toByte())
                    out.add(0x09) // Complete Local Name
                    out.addAll(name)
                }
            return out.toByteArray()
        }

        private const val MAX_ADVERTISEMENT = 31

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

    /**
     * The `startScan(filters, settings, PendingIntent)` overload, which carries
     * no [ScanCallback] for [hookLeScanner] to substitute — results arrive as a
     * broadcast holding the list instead.
     *
     * The receiver cannot be caught the way [hookFoundBroadcast] catches one:
     * the intent's action belongs to the app's own PendingIntent, so there is
     * nothing to match on. Reading the extra is the one step every consumer of
     * this path must take, whatever it does afterwards.
     */
    private fun hookScanBroadcast() {
        "android.content.Intent".toClass().apply {
            if (!hasMethod { name = "getParcelableArrayListExtra" }) return@apply
            method { name = "getParcelableArrayListExtra" }.hookAll {
                after {
                    if (args.firstOrNull() != BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) return@after
                    val snapshot = snapshot() ?: return@after
                    if (!snapshot.hasBeacons) return@after
                    result = ArrayList(snapshot.beacons.mapNotNull { BeaconEmitter.build(it) })
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
                        val beacons = snapshot()?.beacons ?: return@before
                        if (beacons.isEmpty()) return@before
                        val address = (instance as? BluetoothDevice)?.address
                        // Answer for the address being asked about. Returning the
                        // first beacon's name for every device made four results
                        // with four MACs all report one name, which no real scan
                        // produces. An address the recording does not contain
                        // belongs to a genuine device that reached the app some
                        // other way, and stays anonymous rather than leaking its
                        // real name — an unnamed device is ordinary.
                        result = beacons
                            .firstOrNull { it.address.equals(address, ignoreCase = true) }
                            ?.name
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
