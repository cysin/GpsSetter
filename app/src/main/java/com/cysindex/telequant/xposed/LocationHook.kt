package com.cysindex.telequant.xposed

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.content.Context
import android.os.Message
import android.location.Location
import android.os.Bundle
import android.net.wifi.ScanResult
import android.net.wifi.WifiSsid
import android.net.MacAddress
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
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.registerModuleAppActivities
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.type.java.ByteArrayType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.ListClass
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

    }

}
