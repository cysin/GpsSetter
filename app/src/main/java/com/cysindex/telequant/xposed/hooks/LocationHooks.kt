package com.cysindex.telequant.xposed.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.annotation.RequiresApi
import com.cysindex.telequant.xposed.core.LocationDispatcher
import com.cysindex.telequant.xposed.core.LocationFactory
import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.DoubleType
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.hook.type.java.LongType
import com.highcapable.yukihookapi.hook.type.java.StringClass
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

/**
 * Tier A: the position itself. Anything missed here hands the app a real fix.
 */
object LocationHooks : YukiBaseHooker() {

    /** Maps an app's listener to the wrapper actually handed to the framework. */
    private val listenerProxies: MutableMap<Any, Any> =
        Collections.synchronizedMap(WeakHashMap())

    override fun onHook() {
        hookParcelCreator()
        hookLocationAccessors()
        hookLocationManager()
    }

    /**
     * Every [Location] that crosses a Binder boundary into this process is
     * reconstructed here: `getLastKnownLocation`, listener callbacks, and the
     * Locations inside a GMS `LocationResult` all land in this one method.
     *
     * Rewriting here fixes the leak the getter-only approach could not: the
     * object's fields become the spoofed values, so `toString()`,
     * `writeToParcel()` and reflective serialisation agree with the accessors.
     */
    private fun hookParcelCreator() {
        runCatching {
            val creator = Location::class.java.getField("CREATOR").get(null) ?: return
            creator.javaClass.method {
                name = "createFromParcel"
                param(Parcel::class.java)
            }.hook {
                after {
                    (result as? Location)?.let { LocationFactory.applyTo(it) }
                }
            }
        }.onFailure { YLog.warn("Location.CREATOR hook failed: $it") }
    }

    /** Belt and braces on top of the CREATOR rewrite. */
    private fun hookLocationAccessors() {
        Location::class.java.apply {
            hookGetter("getLatitude", DoubleType) { it.lat }
            hookGetter("getLongitude", DoubleType) { it.lng }
            hookGetter("getAltitude", DoubleType) { it.altitude }

            method { name = "getAccuracy"; returnType = FloatType }.hook {
                before { snapshot()?.let { result = it.accuracy } }
            }
            method { name = "getSpeed"; returnType = FloatType }.hook {
                before { snapshot()?.let { result = it.speedMps } }
            }
            method { name = "getBearing"; returnType = FloatType }.hook {
                before { snapshot()?.let { result = it.bearingDeg } }
            }
            method { name = "getTime"; returnType = LongType }.hook {
                before { snapshot()?.let { result = it.timeMillis } }
            }
            method { name = "getElapsedRealtimeNanos"; returnType = LongType }.hook {
                before { snapshot()?.let { result = it.elapsedRealtimeNanos } }
            }

            // Presence flags must agree with the values reported above.
            listOf("hasAltitude", "hasSpeed", "hasBearing", "hasAccuracy").forEach { flag ->
                if (!hasMethod { name = flag; emptyParam() }) return@forEach
                method { name = flag; emptyParam() }.hook {
                    before { if (SpoofEngine.current().enabled) result = true }
                }
            }

            // Mock markers.
            listOf("isMock", "isFromMockProvider").forEach { probe ->
                if (!hasMethod { name = probe; emptyParam() }) return@forEach
                method { name = probe; emptyParam() }.hook {
                    before { if (SpoofEngine.current().enabled) result = false }
                }
            }
            listOf("setMock", "setIsFromMockProvider").forEach { setter ->
                if (!hasMethod { name = setter; param(BooleanType) }) return@forEach
                method { name = setter; param(BooleanType) }.hook {
                    before { if (SpoofEngine.current().enabled) args[0] = false }
                }
            }

            method { name = "set"; param(Location::class.java) }.hook {
                after { (args[0] as? Location)?.let { LocationFactory.applyTo(it) } }
            }

            if (hasMethod { name = "getExtras"; emptyParam() }) {
                method { name = "getExtras"; emptyParam() }.hook {
                    after {
                        val snapshot = snapshot() ?: return@after
                        val extras = result as? Bundle ?: return@after
                        if (extras.containsKey("mockLocation")) {
                            extras.putBoolean("mockLocation", false)
                        }
                        if (extras.containsKey("noGPSLocation")) {
                            @Suppress("DEPRECATION")
                            (extras.getParcelable("noGPSLocation") as? Location)?.let { inner ->
                                inner.latitude = snapshot.lat
                                inner.longitude = snapshot.lng
                                extras.putParcelable("noGPSLocation", inner)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Class<*>.hookGetter(
        methodName: String,
        type: Class<*>,
        pick: (SpoofEngine.Snapshot) -> Double
    ) {
        method { name = methodName; returnType = type }.hook {
            before { snapshot()?.let { result = pick(it) } }
        }
    }

    private fun hookLocationManager() {
        val lm = "android.location.LocationManager".toClass()

        lm.apply {
            method {
                name = "getLastKnownLocation"
                param(StringClass)
                returnType = Location::class.java
            }.hook {
                before {
                    if (!SpoofEngine.current().enabled) return@before
                    result = LocationFactory.build(args[0] as? String ?: LocationManager.GPS_PROVIDER)
                }
            }

            // API 30+. Previously unhooked, so it fell through to whatever the
            // platform produced and only the getters covered it.
            if (hasMethod { name = "getCurrentLocation" }) {
                method { name = "getCurrentLocation" }.hookAll {
                    before {
                        if (!SpoofEngine.current().enabled) return@before
                        val provider = args.firstOrNull { it is String } as? String
                        val consumer = args.lastOrNull { it is java.util.function.Consumer<*> }
                        val executor = args.firstOrNull { it is Executor } as? Executor
                        if (consumer == null) return@before

                        val location = LocationFactory.build(provider ?: LocationManager.GPS_PROVIDER)
                        LocationDispatcher.DeliveryTarget.of(executor).post {
                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                (consumer as java.util.function.Consumer<Location>).accept(location)
                            }
                        }
                        result = null
                    }
                }
            }

            if (hasMethod { name = "requestSingleUpdate" }) {
                method { name = "requestSingleUpdate" }.hookAll {
                    before {
                        if (!SpoofEngine.current().enabled) return@before
                        val listener = args.firstOrNull { it is LocationListener } as? LocationListener
                            ?: return@before
                        val provider = args.firstOrNull { it is String } as? String
                        val looper = args.firstOrNull { it is Looper } as? Looper
                        val location = LocationFactory.build(provider ?: LocationManager.GPS_PROVIDER)
                        LocationDispatcher.DeliveryTarget.of(looper).post {
                            runCatching { listener.onLocationChanged(location) }
                        }
                        result = null
                    }
                }
            }

            hookRequestLocationUpdates(this)
            hookRemoveUpdates(this)
            hookProviderState(this)
            hookProximityAlert(this)
        }
    }

    /**
     * Wraps the app's listener instead of spawning a thread for it. The wrapper
     * rewrites each delivered Location, and [LocationDispatcher] synthesises
     * updates on the app's own Looper/Executor when the platform delivers none.
     */
    private fun hookRequestLocationUpdates(lm: Class<*>) {
        lm.method { name = "requestLocationUpdates" }.hookAll {
            before {
                val listener = args.firstOrNull { it is LocationListener } as? LocationListener
                    ?: return@before
                val provider = args.firstOrNull { it is String } as? String
                val looper = args.firstOrNull { it is Looper } as? Looper
                val executor = args.firstOrNull { it is Executor } as? Executor
                val interval = args.firstOrNull { it is Long } as? Long ?: 1_000L

                val proxy = listenerProxies.getOrPut(listener) {
                    SpoofingLocationListener(listener)
                } as SpoofingLocationListener

                // Hand the framework our wrapper so real updates get rewritten.
                val index = args.indexOfFirst { it is LocationListener }
                if (index >= 0) args[index] = proxy

                val target = if (executor != null) {
                    LocationDispatcher.DeliveryTarget.of(executor)
                } else {
                    LocationDispatcher.DeliveryTarget.of(looper)
                }

                LocationDispatcher.register(listener, provider, interval, target) { location ->
                    runCatching { listener.onLocationChanged(location) }
                }
            }
        }
    }

    private fun hookRemoveUpdates(lm: Class<*>) {
        lm.method { name = "removeUpdates" }.hookAll {
            before {
                val listener = args.firstOrNull { it is LocationListener } ?: return@before
                // Cancels a scheduled task; nothing to join, so no main-thread stall.
                LocationDispatcher.unregister(listener)
                listenerProxies.remove(listener)?.let { proxy ->
                    val index = args.indexOfFirst { it is LocationListener }
                    if (index >= 0) args[index] = proxy
                }
            }
        }
    }

    private fun hookProviderState(lm: Class<*>) {
        lm.apply {
            if (hasMethod { name = "isProviderEnabled"; param(StringClass) }) {
                method { name = "isProviderEnabled"; param(StringClass) }.hook {
                    before { if (SpoofEngine.current().enabled) result = true }
                }
            }
            if (hasMethod { name = "isLocationEnabled"; emptyParam() }) {
                method { name = "isLocationEnabled"; emptyParam() }.hook {
                    before { if (SpoofEngine.current().enabled) result = true }
                }
            }
            if (hasMethod { name = "getProviders"; param(BooleanType) }) {
                method { name = "getProviders"; param(BooleanType) }.hook {
                    after {
                        if (!SpoofEngine.current().enabled) return@after
                        result = SPOOFED_PROVIDERS.toMutableList()
                    }
                }
            }
        }
    }

    /**
     * Proximity alerts are evaluated by the system against the *real* position,
     * so leaving them alone is a genuine leak: an app learns where the device
     * actually is from which alert fires.
     */
    private fun hookProximityAlert(lm: Class<*>) {
        if (!lm.hasMethod { name = "addProximityAlert" }) return
        lm.method { name = "addProximityAlert" }.hookAll {
            before {
                if (SpoofEngine.current().enabled) {
                    result = null
                    YLog.debug("[$packageName] suppressed addProximityAlert")
                }
            }
        }
    }

    private fun snapshot(): SpoofEngine.Snapshot? =
        SpoofEngine.current().takeIf { it.enabled }

    private val SPOOFED_PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        "fused"
    )

    /**
     * Forwards to the app's listener after rewriting the Location. Runs on
     * whatever thread the framework chose, which is the one the app asked for.
     */
    private class SpoofingLocationListener(
        private val delegate: LocationListener
    ) : LocationListener {

        override fun onLocationChanged(location: Location) {
            LocationFactory.applyTo(location)
            delegate.onLocationChanged(location)
        }

        // The batched and flush callbacks were only added to the interface in
        // API 31. The framework never invokes them below that, but the forward
        // to the delegate would be a NoSuchMethodError if it somehow did.
        @RequiresApi(Build.VERSION_CODES.S)
        override fun onLocationChanged(locations: MutableList<Location>) {
            locations.forEach { LocationFactory.applyTo(it) }
            delegate.onLocationChanged(locations)
        }

        override fun onProviderEnabled(provider: String) = delegate.onProviderEnabled(provider)

        override fun onProviderDisabled(provider: String) {
            // Claiming a provider went away contradicts isProviderEnabled().
            if (SpoofEngine.current().enabled) return
            delegate.onProviderDisabled(provider)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        override fun onFlushComplete(requestCode: Int) = delegate.onFlushComplete(requestCode)

        @Deprecated("Deprecated in the platform, still delivered on older paths")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            delegate.onStatusChanged(provider, status, extras)
        }
    }
}
