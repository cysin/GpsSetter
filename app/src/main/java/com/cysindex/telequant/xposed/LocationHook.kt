package com.cysindex.telequant.xposed

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.content.Context
import android.os.Message
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiSsid
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
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
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.registerModuleAppActivities
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.type.java.ByteArrayType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.ListClass
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
import kotlin.math.cos

object LocationHook : YukiBaseHooker() {


    var newlat: Double = 40.7128
    var newlng: Double = 74.0060
    private const val pi = 3.14159265359
    private var accuracy : Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    var mLastUpdated: Long = 0
    private const val className = "android.location.Location"
    //private val ignorePkg = arrayListOf("com.android.location.fused",BuildConfig.APPLICATION_ID)
    private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }



    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat = if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng = if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng
            accuracy = settings.accuracy!!.toFloat()

        }catch (e: Exception) {
            Timber.tag("TeleQuant").e(e, "Failed to get XposedSettings for %s", context.packageName)
        }

    }


    @SuppressLint("NewApi")
    override fun onHook() {

        /*
        loadSystem {
            if (settings.isStarted && (settings.isHookedSystem && !ignorePkg.contains(packageName))) {
                if (System.currentTimeMillis() - mLastUpdated > 200){
                    updateLocation()
                }

                findClass(  "com.android.server.LocationManagerService").hook {
                    injectMember {
                        method {
                            name = "getLastLocation"
                            param(
                                LocationRequest::class.java,
                                String::class.java
                            )
                        }
                        beforeHook {
                            val location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = 0F
                            location.accuracy = accuracy
                            location.speedAccuracyMetersPerSecond = 0F
                            result = location
                        }
                    }

                    injectMember {
                        method {
                            name = "addGnssBatchingCallback"
                            returnType = BooleanType
                        }
                        replaceToFalse()
                    }
                    injectMember {
                        method {
                            name = "addGnssMeasurementsListener"
                            returnType = BooleanType
                        }
                        replaceToFalse()
                    }
                    injectMember {
                        method {
                            name = "addGnssNavigationMessageListener"
                            returnType = BooleanType
                        }
                        replaceToFalse()
                    }

                }
                findClass("com.android.server.LocationManagerService.Receiver").hook {
                    injectMember {
                        method {
                            name = "callLocationChangedLocked"
                            param(Location::class.java)
                        }
                        beforeHook {
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
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
                            } catch (e: Exception) {
                                YLog.warn("LocationHook:- GS: Not possible to mock  $e")
                                //loggerW("LocationHook:- ","GS: Not possible to mock  $e")
                            }
                            args[0] = location


                        }
                    }
                }


            }
        }
        */

        /*
        "com.qualcomm.location.LocationService".toClass().apply {
            method {
                name = "handleMessage"
                param(
                    android.os.Message::class.java
                )
                returnType = BooleanType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)) {
                        lateinit var msg: Message
                        msg = args(0).any() as Message
                        msg.what = 0
                        args[0] = msg
                        XposedBridge.log("[${packageName}] - 'handleMessage'")
                    }
                }

            }
        }
        */

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

        "android.net.wifi.ScanResult".toClass().apply {
            method {
                name = "getWifiSsid"
                emptyParam()
                returnType = WifiSsid::class.java
            }.hook {
                before {
                    if(settings.isStarted && !ignorePkg.contains(packageName)) {
                        replaceTo(null)
                        result = null
                        XposedBridge.log("[${packageName}] - getWifiSsid")

                    }
                }
            }
        }

        "android.net.wifi.WifiSsid".toClass().apply {
            method {
                name = "getBytes"
                emptyParam()
                returnType = ByteArrayType
            }.hook {
                before {
                    if(settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyByteArray = byteArrayOf()
                        replaceTo(emptyByteArray)
                        result = emptyByteArray
                        XposedBridge.log("[${packageName}] - getBytes")

                    }
                }
            }

            method {
                name = "toString"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if(settings.isStarted && !ignorePkg.contains(packageName)) {
                        val emptyString = ""
                        replaceTo(emptyString)
                        result = emptyString
                        XposedBridge.log("[${packageName}] - toString")

                    }
                }
            }

        }

        "android.telephony.CellIdentityLte".toClass().apply {
            method {
                name = "getCi"
                emptyParam()
                returnType = IntType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        val newcid: Long = 10510196757
                        replaceTo(newcid)

                        result = newcid
                        XposedBridge.log("[${packageName}] - getCi")
                    }
                }
            }

            method {
                name = "getMcc"
                emptyParam()
                returnType = IntType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newmcc: Int = 460
                        replaceTo(newmcc)

                        result = newmcc
                        XposedBridge.log("[${packageName}] - getMcc")
                    }
                }
            }

            method {
                name = "getMccString"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newmccstring: String = "460"
                        replaceTo(newmccstring)

                        result = newmccstring
                        XposedBridge.log("[${packageName}] - getMccString")
                    }
                }
            }

            method {
                name = "getMnc"
                emptyParam()
                returnType = IntType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newmnc: Int = 0
                        replaceTo(newmnc)

                        result = newmnc
                        XposedBridge.log("[${packageName}] - getMnc")
                    }
                }
            }

            method {
                name = "getMncString"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newmncstring: String = "00"
                        replaceTo(newmncstring)

                        result = newmncstring
                        XposedBridge.log("[${packageName}] - getMncString")
                    }
                }
            }

            method {
                name = "getTac"
                emptyParam()
                returnType = IntType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newtac: Int = 6815749
                        replaceTo(newtac)

                        result = newtac
                        XposedBridge.log("[${packageName}] - getTac")
                    }
                }
            }

            method {
                name = "getPci"
                emptyParam()
                returnType = IntType
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newpci: Int = 999
                        replaceTo(newpci)

                        result = newpci
                        XposedBridge.log("[${packageName}] - getPci")
                    }
                }
            }

            method {
                name = "toString"
                emptyParam()
                returnType = StringClass
            }.hook {
                before {
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        var newstring: String = "-"
                        replaceTo(newstring)

                        result = newstring
                        XposedBridge.log("[${packageName}] - toString")
                    }
                }
            }

        }

        "android.location.Location".toClass().apply {
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
                        XposedBridge.log("[${packageName}] - 'getLatitude' : ${newlat}")
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
                        XposedBridge.log("[${packageName}] - 'getLongitude' : ${newlng}")
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
                        XposedBridge.log("[${packageName}] - 'getAccuracy' : ${accuracy}")
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
                        XposedBridge.log("[${packageName}] - 'isMock' ")
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
                        XposedBridge.log("[${packageName}] - 'isFromMockProvider' ")
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
                        XposedBridge.log("[${packageName}] - 'set' lat: ${location.latitude}, lon: ${location.longitude}")

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
                name = "getLastKnownLocation"
                param(String::class.java)
            }.hook {
                before {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
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
        /*
        findClass(className).hook {
            injectMember {
                method {
                    name = "getLatitude"
                    returnType = DoubleType
                }
                beforeHook {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        result = newlat
                    }
                }
            }

            injectMember {
                method {
                    name = "getLongitude"
                    returnType = DoubleType
                }
                beforeHook {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        result = newlng
                    }
                }
            }

            injectMember {
                method {
                    name = "getAccuracy"
                    returnType = FloatType
                }
                beforeHook {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
                        updateLocation()
                    }
                    if (settings.isStarted && !ignorePkg.contains(packageName)){
                        result = accuracy
                    }
                }
            }


            injectMember {
                method {
                    name = "set"
                    param(Location::class.java)
                }
                beforeHook {
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
                        XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                        try {
                            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
                        } catch (e: Exception) {
                            loggerW("LocationHook:- ","GS: Not possible to mock  $e")
                        }
                        args[0] = location

                    }

                }
            }

        }

        findClass("android.location.LocationManager").hook {
            injectMember {
                method {
                    name = "getLastKnownLocation"
                    param(String::class.java)
                }
                beforeHook {
                    if (System.currentTimeMillis() - mLastUpdated > 200){
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
                        XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                        try {
                            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
                        } catch (e: Exception) {
                            XposedBridge.log("GS: Not possible to mock (Pre Q)! $e")
                        }
                        result = location


                    }

                }
            }
        }
        */

    }

}
