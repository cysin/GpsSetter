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
            loadHooker(LocationHooks)
            loadHooker(GnssHooks)
            loadHooker(CellHooks)
            loadHooker(WifiHooks)
            loadHooker(BluetoothHooks)
            loadHooker(ConsistencyHooks)
        }
    }
}
