package com.cysindex.telequant.xposed

import com.cysindex.telequant.BuildConfig
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedBridge

@InjectYukiHookWithXposed(modulePackageName = BuildConfig.APPLICATION_ID)
class HookEntry : IYukiHookXposedInit {



    override fun onInit() = configs {
        isEnableHookSharedPreferences = true
        isEnableModulePrefsCache = true


    }


    override fun onHook() = encase {
        loadHooker(LocationHook)
    }


}