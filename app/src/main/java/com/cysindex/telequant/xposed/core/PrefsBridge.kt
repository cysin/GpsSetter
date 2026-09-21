package com.cysindex.telequant.xposed.core

import android.content.Context
import android.os.SystemClock
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.xposed.core.source.ConfigRouter
import com.cysindex.telequant.xposed.core.source.ConfigSource
import com.cysindex.telequant.xposed.core.source.DiskCacheSource
import com.cysindex.telequant.xposed.core.source.ProviderSource
import com.cysindex.telequant.xposed.core.source.XSharedPrefsSource
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * The hooked process's view of the module's settings.
 *
 * Which route they arrive by is decided here and nowhere else, because it is
 * not a property of the build: the same APK is a module under Vector, where
 * the settings are a readable file, and under LSPatch, where they are not and
 * have to be asked for. The first route that answers is kept, and the others
 * are tried again only when it stops answering — so the ordinary case costs
 * one stat, exactly as it did when preferences were the only possibility.
 *
 * Hooks like `Location.getLatitude` reach this through SpoofEngine at very
 * high rates, so every route holds its own copy and none of them does real
 * work per call.
 */
internal object PrefsBridge {

    private var contextProvider: (() -> Context?)? = null

    /**
     * Hands over the hooked app's context, lazily: at the moment hooks are
     * installed the Application does not exist yet, and a route that needs a
     * binder cannot be opened until it does.
     */
    fun attach(context: () -> Context?) {
        contextProvider = context
    }

    private val diskCache by lazy { DiskCacheSource { contextProvider?.invoke() } }

    /** In preference order: a file if it can be read, then the module App, then
     *  whatever this process was last told. */
    private val sources: List<ConfigSource> by lazy {
        listOf(
            XSharedPrefsSource(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs"),
            ProviderSource { contextProvider?.invoke() },
            diskCache
        )
    }

    private val router by lazy {
        ConfigRouter(
            sources = sources,
            clock = { SystemClock.elapsedRealtime() },
            log = { YLog.info(it) }
        )
    }

    fun config(): Config {
        val (source, config) = router.read() ?: return Config.DEFAULT
        // Keeps the fallback copy in step with whatever route is live.
        if (source !== diskCache) diskCache.store(config)
        return config
    }

    private var cachedEnvironmentJson: String? = null
    private var cachedEnvironment: FakeEnvironment? = null

    /** The active recorded environment, or null when only a bare point is set. */
    @Synchronized
    fun environment(config: Config): FakeEnvironment? {
        val json = config.environmentJson
        if (json != cachedEnvironmentJson) {
            cachedEnvironmentJson = json
            cachedEnvironment = FakeEnvironment.parse(json)
        }
        return cachedEnvironment
    }
}
