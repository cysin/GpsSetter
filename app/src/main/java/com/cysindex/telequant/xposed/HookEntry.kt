package com.cysindex.telequant.xposed

import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.xposed.hooks.BluetoothHooks
import com.cysindex.telequant.xposed.hooks.CellHooks
import com.cysindex.telequant.xposed.hooks.ConsistencyHooks
import com.cysindex.telequant.xposed.hooks.GnssHooks
import com.cysindex.telequant.xposed.hooks.LocationHooks
import com.cysindex.telequant.xposed.hooks.WifiHooks
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(modulePackageName = BuildConfig.APPLICATION_ID)
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isEnableHookSharedPreferences = true
        isEnableModulePrefsCache = true
    }

    /**
     * Every hooker runs inside the *target app's* process, never in
     * system_server — which is why the module's recommended scope is empty and
     * the user picks the apps to spoof.
     *
     * Order is deliberate: position first, then the radio environment that has
     * to agree with it, then the consistency checks. They all read the same
     * SpoofEngine snapshot, so they cannot contradict one another.
     */
    override fun onHook() = encase {
        loadApp(isExcludeSelf = true) {
            // Refuse system_server outright. Every hooker here is written for a
            // client process — it rewrites what *this* app is told — and
            // system_server is the process that serves location to every app on
            // the device. Installed there, the hooks reach applications the user
            // never selected, which is exactly what an earlier version did: its
            // recommended scope listed `android`, so following the
            // recommendation quietly spoofed the whole device.
            //
            // isExcludeSelf only excludes this module, so without this a single
            // tick in the manager is enough to reintroduce it.
            if (packageName in PROTECTED_PACKAGES || processName == SYSTEM_SERVER_PROCESS) {
                YLog.warn("refusing to hook $packageName ($processName): not an app process")
                return@loadApp
            }

            loadHooker(LocationHooks)
            loadHooker(GnssHooks)
            loadHooker(CellHooks)
            loadHooker(WifiHooks)
            loadHooker(BluetoothHooks)
            loadHooker(ConsistencyHooks)
        }
    }

    private companion object {
        /** system_server reports this as its package under the Xposed API. */
        val PROTECTED_PACKAGES = setOf("android")
        const val SYSTEM_SERVER_PROCESS = "system_server"
    }
}
