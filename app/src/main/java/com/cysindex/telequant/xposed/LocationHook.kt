package com.cysindex.telequant.xposed

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.content.Context
import android.location.GnssStatus
import android.os.Message
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.net.wifi.ScanResult
import android.net.wifi.WifiSsid
import android.net.MacAddress
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus.Callback
import android.location.LocationRequest
import android.net.NetworkCapabilities
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.gsApp
import com.cysindex.telequant.xposed.LocationHook.hook
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.DoubleType
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.core.annotation.LegacyResourcesHook
import com.highcapable.yukihookapi.hook.factory.applyModuleTheme
import com.highcapable.yukihookapi.hook.factory.constructor
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.registerModuleAppActivities
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.type.java.ByteArrayType
import com.highcapable.yukihookapi.hook.type.java.FloatClass
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.ListClass
import com.highcapable.yukihookapi.hook.type.java.LongClass
import com.highcapable.yukihookapi.hook.type.java.LongType
import com.highcapable.yukihookapi.hook.type.java.StringArrayClass
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.highcapable.yukihookapi.hook.type.java.UnitType
import com.highcapable.yukihookapi.hook.xposed.bridge.event.YukiXposedEvent
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.random.Random
import kotlin.math.*
import kotlin.math.roundToInt

object LocationHook : YukiBaseHooker() {


    var newlat: Double = 40.7128
    var newlng: Double = 74.0060
    private const val pi = 3.14159265359
    private var accuracy : Float = 0.0f
    //private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    var mLastUpdated: Long = 0
    private val ignorePkg = arrayListOf("com.android.location.fused",BuildConfig.APPLICATION_ID)
    //private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }
    private val mygnssstatus:GnssStatus = createRealisticGnssStatus()

    private val locationListenerThreadMap = ConcurrentHashMap<LocationListener, Thread>()
    private val locationListenerRunnableMap = ConcurrentHashMap<LocationListener, locationListenerRunnable>()

    private val gnssStatusListenerThreadMap = ConcurrentHashMap<android.location.GnssStatus.Callback, Thread>()
    private val gnssStatusListenerRunnableMap = ConcurrentHashMap<android.location.GnssStatus.Callback, gnssStatusListenerRunnable>()

    // Constants for the conversion
    private const val EARTH_RADIUS = 6378245.0 // Semi-major axis
    private const val EE = 0.00669342162296594323 // Flattening

    // Transform latitude
    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    // Transform longitude
    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    // Convert WGS84 to GCJ-02
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)

        val mgLat = lat + (dLat * 180.0) / ((EARTH_RADIUS * (1 - EE)) / (magic * sqrtMagic) * PI)
        val mgLng = lng + (dLng * 180.0) / (EARTH_RADIUS / sqrtMagic * cos(radLat) * PI)

        return Pair(mgLat, mgLng)
    }

    private fun <T> List<T>.random(weights: DoubleArray): T {
        val total = weights.sum()
        var random = Random.nextDouble() * total
        for (i in indices) {
            random -= weights[i]
            if (random <= 0) return this[i]
        }
        return last()
    }

    private fun createRealisticGnssStatus(numSatellites: Int = Random.nextInt(4, 15)): GnssStatus {
        val builder = GnssStatus.Builder()

        // Helper function to generate realistic carrier frequencies based on constellation
        fun getCarrierFrequency(constellation: Int): Float {
            return when (constellation) {
                GnssStatus.CONSTELLATION_GPS -> listOf(1575.42f, 1227.60f, 1176.45f).random() // L1, L2, L5
                GnssStatus.CONSTELLATION_GLONASS -> listOf(1602.0f, 1246.0f).random() // L1, L2
                GnssStatus.CONSTELLATION_GALILEO -> listOf(1575.42f, 1176.45f).random() // E1, E5a
                GnssStatus.CONSTELLATION_BEIDOU -> listOf(1561.098f, 1207.140f).random() // B1, B2
                else -> 1575.42f // Default to GPS L1
            }
        }

        // Helper function to generate realistic SVID based on constellation
        fun getSvidRange(constellation: Int): IntRange {
            return when (constellation) {
                GnssStatus.CONSTELLATION_GPS -> 1..32
                GnssStatus.CONSTELLATION_GLONASS -> 1..24
                GnssStatus.CONSTELLATION_GALILEO -> 1..36
                GnssStatus.CONSTELLATION_BEIDOU -> 1..63
                GnssStatus.CONSTELLATION_QZSS -> 1..10
                else -> 1..40
            }
        }

        // Available constellations with weighted probability
        val constellations = listOf(
            GnssStatus.CONSTELLATION_GPS to 0.4,
            GnssStatus.CONSTELLATION_GLONASS to 0.2,
            GnssStatus.CONSTELLATION_GALILEO to 0.2,
            GnssStatus.CONSTELLATION_BEIDOU to 0.15,
            GnssStatus.CONSTELLATION_QZSS to 0.05
        )

        // Track used SVIDs per constellation to avoid duplicates
        val usedSvids = mutableMapOf<Int, MutableSet<Int>>()

        repeat(numSatellites) {
            // Select constellation based on weighted probability
            val constellation = constellations.random(
                weights = constellations.map { it.second }.toDoubleArray()
            ).first

            // Ensure unique SVID for constellation
            val svidRange = getSvidRange(constellation)
            val usedConstellationSvids = usedSvids.getOrPut(constellation) { mutableSetOf() }
            val svid = (svidRange.toList() - usedConstellationSvids).randomOrNull()
                ?: svidRange.random() // Fallback if all SVIDs are used
            usedConstellationSvids.add(svid)

            // Generate realistic signal strength (usually between 20-50 dB-Hz)
            val cn0DbHz = Random.nextDouble(20.0, 50.0).toFloat()

            // Generate realistic elevation (0-90 degrees)
            val elevation = Random.nextDouble(0.0, 90.0).toFloat()

            // Generate realistic azimuth (0-360 degrees)
            val azimuth = Random.nextDouble(0.0, 360.0).toFloat()

            // Satellites with higher elevation and CN0 are more likely to be used in fix
            val usedInFix = (elevation > 15 && cn0DbHz > 25 && Random.nextDouble() < 0.8)

            // Higher elevation satellites are more likely to have ephemeris and almanac
            val hasEphemeris = Random.nextDouble() < (0.7 + elevation / 180.0)
            val hasAlmanac = hasEphemeris || Random.nextDouble() < 0.9

            // Most modern receivers have carrier frequency
            val hasCarrierFrequency = Random.nextDouble() < 0.95
            val carrierFrequency = getCarrierFrequency(constellation)

            // Baseband CN0 is usually slightly lower than regular CN0
            val hasBasebandCn0DbHz = Random.nextDouble() < 0.8
            val basebandCn0DbHz = (cn0DbHz - Random.nextDouble(1.0, 3.0)).toFloat()

            builder.addSatellite(
                constellation,
                svid,
                cn0DbHz,
                elevation,
                azimuth,
                hasEphemeris,
                hasAlmanac,
                usedInFix,
                hasCarrierFrequency,
                carrierFrequency,
                hasBasebandCn0DbHz,
                basebandCn0DbHz
            )
        }

        return builder.build()
    }

    private class locationListenerRunnable(
        private val providerName:String,
        private val listener: android.location.LocationListener
    ) : Runnable {
        @Volatile
        private var isRunning = true

        fun isRunning(): Boolean {
            return isRunning
        }

        fun stopThread() {
            isRunning = false
        }

        override fun run() {
            while (isRunning) {
                try {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        if (System.currentTimeMillis() - mLastUpdated > 200) {
                            updateLocation()
                        }

                        lateinit var location: Location

                        location = Location(providerName)
                        location.time = System.currentTimeMillis() - 300
                        location.accuracy = accuracy

                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = 0.0
                        location.speed = 0F
                        location.speedAccuracyMetersPerSecond = 0F
                        listener.onLocationChanged(location)
                        //XposedBridge.log("[${packageName}] - 'locationlistener3' onLocationChanged called")
                    }
                    var sleepTime:Long = Random.nextLong(300,2000)
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    XposedBridge.log("[${packageName}] - 'locationListenerRunnable' exception ${e}")
                }
                // Simulate doing some work

                //try {
                //    Thread.sleep(500) // Simulating a task, adjust as needed
                //} catch (e: InterruptedException) {
                //    Thread.currentThread().interrupt()
                //}
            }
            XposedBridge.log("[${packageName}] - 'locationListenerRunnable' thread stopped")
        }
    }

    private class gnssStatusListenerRunnable(
        private val callback: android.location.GnssStatus.Callback,
    ) : Runnable {
        @Volatile
        private var isRunning = true

        fun isRunning(): Boolean {
            return isRunning
        }

        fun stopThread() {
            isRunning = false
        }

        override fun run() {
            val callbackClass = callback::class.java

            if(callbackClass.hasMethod {
                    name = "onSatelliteStatusChanged"
                }) {
                callbackClass.method {
                    name = "onSatelliteStatusChanged"
                }.hook {
                    before {
                        if (settings.isStarted && !ignorePkg.contains(packageName)) {
                            args[0] = mygnssstatus
                        }
                        //XposedBridge.log("[${packageName}] - 'onSatelliteStatusChanged' : mygnssstatus")
                    }
                }
            }
            callback.onStarted()
            callback.onFirstFix(1000)
            while (isRunning) {
                try {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        callback.onSatelliteStatusChanged(mygnssstatus)
                    }
                    var sleepTime:Long = Random.nextLong(300,2000)
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    XposedBridge.log("[${packageName}] - 'gnssStatusListenerRunnable' exception ${e}")
                }

            }
            XposedBridge.log("[${packageName}] - 'gnssStatusListenerRunnable' thread stopped")
        }
    }


    private fun createGnssStatusCallback( callback: android.location.GnssStatus.Callback) {
        if (settings.isStarted && !ignorePkg.contains(packageName)) {
            val callbackClass = callback::class.java

            if(callbackClass.hasMethod {
                    name = "onSatelliteStatusChanged"
                }) {
                callbackClass.method {
                    name = "onSatelliteStatusChanged"
                }.hook {
                    before {
                        args[0] = mygnssstatus
                        //XposedBridge.log("[${packageName}] - 'onSatelliteStatusChanged' : mygnssstatus")
                    }
                }
            }

            Thread {
                callback.onStarted()
                callback.onFirstFix(1000)
                for (i in 1..12) {
                    callback.onSatelliteStatusChanged(mygnssstatus)
                    Thread.sleep(800)
                }
            }.start()

        }
    }

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (Random.nextInt(30) - 15).toDouble()
            val y = (Random.nextInt(30) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat = if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng = if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng

            if(true) {
                val (gcj02Lat, gcj02Lng) = wgs84ToGcj02(newlat, newlng)
                newlat = gcj02Lat
                newlng = gcj02Lng

            }
            accuracy = settings.accuracy!!.toFloat()

        }catch (e: Exception) {
            Timber.tag("TeleQuant").e(e, "Failed to get XposedSettings for %s", context.packageName)
        }

    }


    @SuppressLint("NewApi")
    override fun onHook() {
        val settingsClasses = arrayOf(
            "android.provider.Settings.Secure",
            "android.provider.Settings.System",
            "android.provider.Settings.Global",
            "android.provider.Settings.NameValueCache"
        )


        settingsClasses.forEach { className ->
            if (!className.hasClass()) return@forEach

            className.toClass().method {
                name = "getStringForUser"
                paramCount = 2  // Assuming method has 2 parameters
            }.hookAll {
                replaceUnit {
                    val name = args[1] as? String
                    result = when (name) {
                        "mock_location" -> "0"
                        else -> try {
                            callOriginal()
                        } catch (e: Exception) {
                            YLog.warn("${className}: hook error $e")
                            //throwable(e)
                            //null
                        }
                    }
                }
            }
        }

        val cellIdClasses = arrayOf(
            "android.telephony.CellIdentityNr",
            "android.telephony.CellIdentityGsm",
            "android.telephony.CellIdentityCdma",
            "android.telephony.CellIdentityWcdma",
            "android.telephony.CellIdentityTdscdma",
            "android.telephony.CellIdentityLte"
        )

        cellIdClasses.forEach { className ->
            if (!className.hasClass()) return@forEach

            val cellIDIns = className.toClass()

            cellIDIns.apply {

                if(cellIDIns.hasMethod {
                        name = "getCid"
                        emptyParam()
                        returnType = IntType
                    }) {

                    method {
                        name = "getCid"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newcid: Int = 0
                                replaceTo(newcid)

                                result = newcid
                                XposedBridge.log("[${packageName}] ${className} - getCid")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getCi"
                        emptyParam()
                        returnType = IntType
                    }) {

                    method {
                        name = "getCi"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newci: Int = 0
                                replaceTo(newci)

                                result = newci
                                XposedBridge.log("[${packageName}] ${className} - getCi")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getNci"
                        emptyParam()
                        returnType = LongType
                    }) {
                    method {
                        name = "getNci"
                        emptyParam()
                        returnType = LongType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newcid: Long = 0
                                replaceTo(newcid)

                                result = newcid
                                XposedBridge.log("[${packageName}] ${className} - getNci")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getMcc"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getMcc"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newmcc: Int = 460
                                replaceTo(newmcc)

                                result = newmcc
                                XposedBridge.log("[${packageName}] ${className} - getMcc")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getMccString"
                        emptyParam()
                        returnType = StringClass
                    }) {
                    method {
                        name = "getMccString"
                        emptyParam()
                        returnType = StringClass
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newmccstring: String = "460"
                                replaceTo(newmccstring)

                                result = newmccstring
                                XposedBridge.log("[${packageName}] ${className} - getMccString")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getMnc"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getMnc"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newmnc: Int = 0
                                replaceTo(newmnc)

                                result = newmnc
                                XposedBridge.log("[${packageName}] ${className} - getMnc")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getMncString"
                        emptyParam()
                        returnType = StringClass
                    }) {
                    method {
                        name = "getMncString"
                        emptyParam()
                        returnType = StringClass
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newmncstring: String = "00"
                                replaceTo(newmncstring)

                                result = newmncstring
                                XposedBridge.log("[${packageName}] ${className} - getMncString")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getLac"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getLac"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newlac: Int = 0//6815749
                                replaceTo(newlac)

                                result = newlac
                                XposedBridge.log("[${packageName}] ${className} - getLac")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getPsc"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getPsc"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newpsc: Int = 0//999
                                replaceTo(newpsc)

                                result = newpsc
                                XposedBridge.log("[${packageName}] ${className} - getPsc")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getBsic"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getBsic"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newbsic: Int = 0//999
                                replaceTo(newbsic)

                                result = newbsic
                                XposedBridge.log("[${packageName}] ${className} - getBsic")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "toString"
                        emptyParam()
                        returnType = StringClass
                    }) {
                    method {
                        name = "toString"
                        emptyParam()
                        returnType = StringClass
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newstring: String = "-"
                                replaceTo(newstring)

                                result = newstring
                                XposedBridge.log("[${packageName}] ${className} - toString")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getBasestationId"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getBasestationId"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newid: Int = 0
                                replaceTo(newid)

                                result = newid
                                XposedBridge.log("[${packageName}] ${className} - getBasestationId")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getLatitude"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getLatitude"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newid: Int = 0
                                replaceTo(newid)

                                result = newid
                                XposedBridge.log("[${packageName}] ${className} - getLatitude")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getLongitude"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getLongitude"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newid: Int = 0
                                replaceTo(newid)

                                result = newid
                                XposedBridge.log("[${packageName}] ${className} - getLongitude")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getNetworkId"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getNetworkId"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newid: Int = 0
                                replaceTo(newid)

                                result = newid
                                XposedBridge.log("[${packageName}] ${className} - getNetworkId")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getSystemId"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getSystemId"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                val newid: Int = 0
                                replaceTo(newid)

                                result = newid
                                XposedBridge.log("[${packageName}] ${className} - getSystemId")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getCpid"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getCpid"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newcpid: Int = 0//999
                                replaceTo(newcpid)

                                result = newcpid
                                XposedBridge.log("[${packageName}] ${className} - getCpid")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getPci"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getPci"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newpci: Int = 0//999
                                replaceTo(newpci)

                                result = newpci
                                XposedBridge.log("[${packageName}] ${className} - getPci")
                            }
                        }
                    }
                }

                if(cellIDIns.hasMethod {
                        name = "getTac"
                        emptyParam()
                        returnType = IntType
                    }) {
                    method {
                        name = "getTac"
                        emptyParam()
                        returnType = IntType
                    }.hook {
                        before {
                            if (settings.isStarted && !ignorePkg.contains(packageName)) {
                                var newtac: Int = 0//6815749
                                replaceTo(newtac)

                                result = newtac
                                XposedBridge.log("[${packageName}] ${className} - getTac")
                            }
                        }
                    }
                }

            } //className.toClass().apply

        }

        "android.net.wifi.WifiInfo".toClass().apply {
            method {
                name = "getBSSID"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyString = String()
                        result = emptyString
                        replaceTo(emptyString)
                        XposedBridge.log("[${packageName}] - getBSSID")
                    }
                }
            }

            method {
                name = "getSSID"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyString = String()
                        result = emptyString
                        replaceTo(emptyString)
                        XposedBridge.log("[${packageName}] - getSSID")
                    }
                }
            }

            method {
                name = "toString"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyString = String()
                        result = emptyString
                        replaceTo(emptyString)
                        XposedBridge.log("[${packageName}] - toString")
                    }
                }
            }

        }

        "android.net.wifi.WifiManager".toClass().apply {
            method {
                name = "getScanResults"
                emptyParam()
                returnType = ListClass
            }.hook {
                before {
                    if(settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyList = emptyList<ScanResult>()
                        replaceTo(emptyList)
                        result = emptyList
                        XposedBridge.log("[${packageName}] - getScanResults")

                    }
                }
            }
        }

        val locationIns = "android.location.Location".toClass()
        locationIns.apply {
            method {
                name = "getLatitude"
                returnType = DoubleType
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        replaceTo(newlat)
                        result = newlat
                        //XposedBridge.log("[${packageName}] - 'getLatitude' : ${newlat}")
                    }
                }
            }

            method {
                name = "getLongitude"
                returnType = DoubleType
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        replaceTo(newlng)
                        result = newlng
                        //XposedBridge.log("[${packageName}] - 'getLongitude' : ${newlng}")
                    }
                }
            }

            method {
                name = "getAccuracy"
                returnType = FloatType
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        replaceTo(accuracy)
                        result = accuracy
                        //XposedBridge.log("[${packageName}] - 'getAccuracy' : ${accuracy}")
                    }
                }
            }

            method {
                name = "isMock"
                returnType = BooleanType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        replaceToFalse()
                        result = false
                        //XposedBridge.log("[${packageName}] - 'isMock' ")
                    }
                }
            }

            method {
                name = "isFromMockProvider"
                returnType = BooleanType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        replaceToFalse()
                        result = false
                        //XposedBridge.log("[${packageName}] - 'isFromMockProvider' ")
                    }
                }
            }

            method {
                name = "setMock"
                param(BooleanType)
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        args[0] = false
                    }
                }
            }

            if(locationIns.hasMethod {
                    name = "setIsFromMockProvider"
                    param(BooleanType)
                }) {
                method {
                    name = "setIsFromMockProvider"
                    param(BooleanType)
                }.hook {
                    before {
                        if (settings.isStarted && !ignorePkg.contains(packageName)) {
                            args[0] = false
                        }
                    }
                }
            }

            method {
                name = "getExtras"
                returnType = Bundle::class.java
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        var bundle = callOriginal() as? Bundle
                        if (bundle?.containsKey("mockLocation") == true) {
                            bundle.putBoolean("mockLocation", false)
                        }
                        replaceTo(bundle)
                        result = bundle
                    }
                }
            }

            method {
                name = "setExtras"
                param(Bundle::class.java)
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        var bundle = args(0).any() as Bundle
                        if (bundle?.containsKey("mockLocation") == true) {
                            bundle.putBoolean("mockLocation", false)
                        }
                        args[0] = bundle
                    }
                }
            }

            method {
                name = "set"
                param(Location::class.java)
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        lateinit var location: Location
                        lateinit var originLocation: Location
                        if (args[0] == null){
                            location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                        }else {
                            originLocation = args(0).any() as Location
                            location = Location(originLocation.provider)
                            location.time = originLocation.time
                            location.accuracy = accuracy
                            location.bearing = originLocation.bearing
                            location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                            location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                        }

                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = 0.0
                        location.speed = 0F
                        location.speedAccuracyMetersPerSecond = 0F
                        //XposedBridge.log("[${packageName}] - 'set' lat: ${location.latitude}, lon: ${location.longitude}")

                        /*
                        try {
                            HiddenApiBypass.invoke(
                                location.javaClass,
                                location,
                                "isFromMockProvider",
                                false
                            )
                            HiddenApiBypass.invoke(location.javaClass, location, "isMock", false)
                        } catch (e: Exception) {
                            YLog.warn(msg = "GS: Not possible to mock  $e", tag = "LocationHook set:- ")
                        }
                        */

                        //args().first().set(location)
                        args[0] = location

                    }
                }

            }


        }


        "android.location.LocationManager".toClass().apply {
            method {
                name = "registerGnssStatusCallback"
                param(android.location.GnssStatus.Callback::class.java)
                returnType = BooleanType
            }.hook {
                after {
                    val callback = args[0] as android.location.GnssStatus.Callback
                    if(!gnssStatusListenerThreadMap.containsKey(callback) || !gnssStatusListenerRunnableMap.containsKey(callback)) {
                        val runnable = gnssStatusListenerRunnable(callback)
                        val thread = Thread(runnable)
                        gnssStatusListenerRunnableMap[callback] = runnable
                        gnssStatusListenerThreadMap[callback] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - gnssStatusListener thread started: ${threadid}")
                    }

                }
            }

            method {
                name = "registerGnssStatusCallback"
                param(
                    android.location.GnssStatus.Callback::class.java,
                    android.os.Handler::class.java
                )
                returnType = BooleanType
            }.hook {
                after {
                    val callback = args[0] as android.location.GnssStatus.Callback
                    if(!gnssStatusListenerThreadMap.containsKey(callback) || !gnssStatusListenerRunnableMap.containsKey(callback)) {
                        val runnable = gnssStatusListenerRunnable(callback)
                        val thread = Thread(runnable)
                        gnssStatusListenerRunnableMap[callback] = runnable
                        gnssStatusListenerThreadMap[callback] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - gnssStatusListener thread started: ${threadid}")
                    }

                }
            }

            method {
                name = "registerGnssStatusCallback"
                param(
                    java.util.concurrent.Executor::class.java,
                    android.location.GnssStatus.Callback::class.java,
                )
                returnType = BooleanType
            }.hook {
                after {
                    val callback = args[1] as android.location.GnssStatus.Callback

                    if(!gnssStatusListenerThreadMap.containsKey(callback) || !gnssStatusListenerRunnableMap.containsKey(callback)) {
                        val runnable = gnssStatusListenerRunnable(callback)
                        val thread = Thread(runnable)
                        gnssStatusListenerRunnableMap[callback] = runnable
                        gnssStatusListenerThreadMap[callback] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - gnssStatusListener thread started: ${threadid}")
                    }

                }
            }

            method {
                name = "requestLocationUpdates"
                param(
                    StringClass,
                    LongType,
                    FloatType,
                    LocationListener::class.java
                )
            }.hook {
                after {
                    val listener = args[3] as android.location.LocationListener
                    val providername = args[0] as String

                    if(!locationListenerThreadMap.containsKey(listener) || !locationListenerRunnableMap.containsKey(listener)) {
                        val runnable = locationListenerRunnable(providername, listener)
                        val thread = Thread(runnable)
                        locationListenerRunnableMap[listener] = runnable
                        locationListenerThreadMap[listener] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - locationListener thread started : ${threadid}")
                    }


                }
            }

            method {
                name = "requestLocationUpdates"
                param(
                    StringClass,
                    LongType,
                    FloatType,
                    LocationListener::class.java,
                    Looper::class.java
                )
            }.hook {
                after {
                    val listener = args[3] as android.location.LocationListener
                    val providername = args[0] as String
                    if(!locationListenerThreadMap.containsKey(listener) || !locationListenerRunnableMap.containsKey(listener)) {
                        val runnable = locationListenerRunnable(providername, listener)
                        val thread = Thread(runnable)
                        locationListenerRunnableMap[listener] = runnable
                        locationListenerThreadMap[listener] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - locationListener thread started: ${threadid}")

                    }
                }
            }

            method {
                name = "requestLocationUpdates"
                param(
                    StringClass,
                    LongType,
                    FloatType,
                    java.util.concurrent.Executor::class.java,
                    LocationListener::class.java,

                )
            }.hook {
                after {
                    val listener = args[4] as android.location.LocationListener
                    val providername = args[0] as String
                    if(!locationListenerThreadMap.containsKey(listener) || !locationListenerRunnableMap.containsKey(listener)) {
                        val runnable = locationListenerRunnable(providername, listener)
                        val thread = Thread(runnable)
                        locationListenerRunnableMap[listener] = runnable
                        locationListenerThreadMap[listener] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - locationListener thread started: ${threadid}")

                    }
                }
            }

            method {
                name = "requestLocationUpdates"
                param(
                    StringClass,
                    LocationRequest::class.java,
                    java.util.concurrent.Executor::class.java,
                    LocationListener::class.java,
                    )
            }.hook {
                after {
                    val listener = args[3] as android.location.LocationListener
                    val providername = args[0] as String
                    if(!locationListenerThreadMap.containsKey(listener) || !locationListenerRunnableMap.containsKey(listener)) {
                        val runnable = locationListenerRunnable(providername, listener)
                        val thread = Thread(runnable)
                        locationListenerRunnableMap[listener] = runnable
                        locationListenerThreadMap[listener] = thread

                        thread.start()
                        val threadid = thread.getId()
                        XposedBridge.log("[${packageName}] - locationListener thread started: ${threadid}")

                    }
                }
            }

            method {
                name = "removeUpdates"
                param(
                    LocationListener::class.java
                )
            }.hook {
                after {
                    val listener = args[0] as LocationListener
                    if(locationListenerThreadMap.containsKey(listener) && locationListenerRunnableMap.containsKey(listener)) {
                        val threadid = locationListenerThreadMap[listener]?.getId()
                        locationListenerRunnableMap[listener]?.stopThread()
                        locationListenerThreadMap[listener]?.let { thread ->
                            try {
                                // Wait for the thread to finish with a timeout
                                thread.join(2000)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        locationListenerRunnableMap.remove(listener)
                        locationListenerThreadMap.remove(listener)
                        XposedBridge.log("[${packageName}] - locationListener thread stopped: ${threadid}")

                    } else {
                        XposedBridge.log("[${packageName}] - Error finding locationListener thread or runnable")
                    }

                }
            }

            method {
                name = "unregisterGnssStatusCallback"
                param(
                    android.location.GnssStatus.Callback::class.java
                )
            }.hook {
                after {
                    val callback = args[0] as android.location.GnssStatus.Callback
                    if(gnssStatusListenerThreadMap.containsKey(callback) && gnssStatusListenerRunnableMap.containsKey(callback)) {
                        val threadid = gnssStatusListenerThreadMap[callback]?.getId()
                        gnssStatusListenerRunnableMap[callback]?.stopThread()
                        gnssStatusListenerThreadMap[callback]?.let { thread ->
                            try {
                                // Wait for the thread to finish with a timeout
                                thread.join(2000)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        gnssStatusListenerRunnableMap.remove(callback)
                        gnssStatusListenerThreadMap.remove(callback)
                        XposedBridge.log("[${packageName}] - gnssStatusListener thread stopped: ${threadid}")

                    } else {
                        XposedBridge.log("[${packageName}] - Error finding gnssStatusListener thread or runnable")
                    }

                }
            }

            method {
                name = "getLastKnownLocation"
                param(String::class.java)
                returnType = android.location.Location::class.java
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200) {
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        val provider = args[0] as String
                        val location = Location(provider)
                        location.time = System.currentTimeMillis() - 300
                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = 0.0
                        location.speed = 0F
                        location.speedAccuracyMetersPerSecond = 0F
                        XposedBridge.log("[${packageName}] - 'getLastKnowLocation' : lat: ${location.latitude}, lon: ${location.longitude}")

                        /*
                        try {
                            HiddenApiBypass.invoke(location.javaClass, location, "isMock", false)
                            HiddenApiBypass.invoke(location.javaClass, location, "isFromMockProvider", false)
                        } catch (e: Exception) {
                            XposedBridge.log("'getLastKnowLocation' GS: Not possible to mock (Pre Q)! $e")
                        }
                        */

                        replaceTo(location)
                        result = location
                    }
                }
            }
        }

    }

}
