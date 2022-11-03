package com.android1500.gpssetter.xposed
import com.android1500.gpssetter.BuildConfig
import com.android1500.gpssetter.repository.SettingsRepository
import de.robv.android.xposed.XSharedPreferences

 class Xshare {

    private var xPref: XSharedPreferences? = null
    private fun pref() : XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID,"${BuildConfig.APPLICATION_ID}_prefs")
        return xPref as XSharedPreferences
    }


     val isStarted : Boolean
     get() = pref().getBoolean("start", false)


     val getLat: Double
     get() = pref().getFloat(
         "latitude",
         22.2855200.toFloat()
     ).toDouble()


     val getLng : Double
     get() = pref().getFloat(
         "longitude",
         114.1576900.toFloat()
     ).toDouble()

     val isHookedSystem : Boolean
     get() = pref().getBoolean(
         "isHookedSystem",
         false
     )

     val isRandomPosition :Boolean
     get() = pref().getBoolean(
         "random_position",
         false
     )





}