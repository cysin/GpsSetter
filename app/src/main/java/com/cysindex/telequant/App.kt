package com.cysindex.telequant

import androidx.appcompat.app.AppCompatDelegate
import com.cysindex.telequant.map.TileSourceConfig
import com.cysindex.telequant.utils.PrefManager
import com.google.android.material.color.DynamicColors
import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication
import timber.log.Timber

lateinit var gsApp: App

class App : ModuleApplication() {

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Material 3 dynamic color, replacing the unmaintained MonetCompat.
        DynamicColors.applyToActivitiesIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        // osmdroid reads much of its configuration when a tile provider is
        // constructed, so this has to run before any MapView exists.
        TileSourceConfig.apply(this)
    }
}
