package com.cysindex.telequant.xposed.core.source

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cysindex.telequant.config.ConfigContract
import com.cysindex.telequant.xposed.core.Config
import com.cysindex.telequant.xposed.core.ConfigReader

/**
 * The settings over a binder, which is how they arrive without root.
 *
 * The module App holds them and hands out a copy on request. Two things make
 * that affordable on a path this hot: the answer is kept until the App says it
 * changed, and the App says so by notifying the content URI this observes —
 * nothing polls, and a changed anchor still reaches a running app within a
 * fix. The interval below is only a floor in case the observer never
 * registers, which happens when package visibility hides the App.
 */
internal class ProviderSource(private val context: () -> Context?) : ConfigSource {

    override val name = "ContentProvider"

    @Volatile
    private var cached: Config? = null

    @Volatile
    private var staleAtMillis = 0L

    private var observer: ContentObserver? = null
    private var lastPingAtMillis = 0L

    override fun read(): Config? {
        val current = cached
        if (current != null && SystemClock.elapsedRealtime() < staleAtMillis) return current
        return refresh()
    }

    @Synchronized
    private fun refresh(): Config? {
        val resolver = context()?.contentResolver ?: return null
        val answer = runCatching {
            resolver.call(ConfigContract.CONTENT_URI, ConfigContract.METHOD_READ, null, null)
        }.getOrNull() ?: return null
        // An App that is installed but has the door shut answers deliberately.
        if (!answer.getBoolean(ConfigContract.EXTRA_AVAILABLE, false)) return null

        observe(resolver)
        announce(resolver)

        return Config.from(BundleReader(answer)).also {
            cached = it
            staleAtMillis = SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MILLIS
        }
    }

    private fun observe(resolver: android.content.ContentResolver) {
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val created = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                staleAtMillis = 0L
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(ConfigContract.CONTENT_URI, true, created)
        }.isSuccess
        if (registered) observer = created
    }

    /**
     * Tells the App which package is reading. The drawer has no other way to
     * show that the module is doing anything: this App is not itself hooked
     * under a rootless framework, so it cannot observe its own effect.
     */
    private fun announce(resolver: android.content.ContentResolver) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPingAtMillis < PING_INTERVAL_MILLIS) return
        lastPingAtMillis = now
        runCatching { resolver.call(ConfigContract.CONTENT_URI, ConfigContract.METHOD_PING, null, null) }
    }

    private class BundleReader(private val bundle: Bundle) : ConfigReader {
        override fun contains(key: String) = bundle.containsKey(key)
        override fun bool(key: String, fallback: Boolean) = bundle.getBoolean(key, fallback)
        override fun long(key: String, fallback: Long) = bundle.getLong(key, fallback)
        override fun float(key: String, fallback: Float) = bundle.getFloat(key, fallback)
        override fun string(key: String): String? = bundle.getString(key)
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 30_000L
        const val PING_INTERVAL_MILLIS = 5 * 60_000L
    }
}
