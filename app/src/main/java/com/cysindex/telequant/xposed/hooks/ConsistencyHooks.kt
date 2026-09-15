package com.cysindex.telequant.xposed.hooks

import com.cysindex.telequant.xposed.core.SpoofEngine
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.hasClass
import com.highcapable.yukihookapi.hook.factory.hasMethod
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.type.java.StringClass
import java.util.TimeZone

/**
 * Tier C: the checks that catch a spoof whose position is right but whose
 * surroundings disagree with it.
 *
 * Also carries the mock-location suppression, which in the old code was dead
 * twice over: the nested Settings classes were named with dots instead of `$`
 * so the lookup never resolved, and the arity was wrong even if it had.
 */
object ConsistencyHooks : YukiBaseHooker() {

    private val mockKeys = setOf("mock_location", "allow_mock_location")

    override fun onHook() {
        hookMockLocationSetting()
        hookTimeZone()
    }

    private fun hookMockLocationSetting() {
        // Binary names: nested classes need '$'. The previous "Settings.Secure"
        // form never resolved, so this whole block silently did nothing.
        val settingsClasses = arrayOf(
            "android.provider.Settings\$Secure",
            "android.provider.Settings\$System",
            "android.provider.Settings\$Global",
            "android.provider.Settings\$NameValueCache"
        )

        settingsClasses.forEach { className ->
            if (!className.hasClass()) return@forEach
            val clazz = className.toClass()

            // Hook every arity rather than pinning one: the signature differs
            // across API levels and OEM forks.
            listOf("getStringForUser", "getString").forEach { methodName ->
                if (!clazz.hasMethod { name = methodName }) return@forEach
                clazz.method { name = methodName }.hookAll {
                    before {
                        if (!SpoofEngine.current().enabled) return@before
                        val key = args.firstOrNull { it is String } as? String
                        if (key in mockKeys) result = "0"
                    }
                }
            }
        }
    }

    /**
     * A device claiming to be in Shanghai while its clock runs on
     * America/New_York is an obvious contradiction. Off by default, because
     * rewriting the default time zone affects every date the app formats and
     * some apps handle that badly.
     */
    private fun hookTimeZone() {
        val className = "java.util.TimeZone"
        if (!className.hasClass()) return
        val clazz = className.toClass()

        if (!clazz.hasMethod { name = "getDefault"; emptyParam() }) return
        clazz.method { name = "getDefault"; emptyParam() }.hook {
            after {
                val snapshot = SpoofEngine.current()
                if (!snapshot.enabled || !snapshot.spoofTimeZone) return@after
                val id = snapshot.timeZoneId ?: return@after
                runCatching { result = TimeZone.getTimeZone(id) }
            }
        }
    }
}
