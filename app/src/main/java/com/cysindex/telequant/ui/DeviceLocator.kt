package com.cysindex.telequant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/**
 * Reads where the device actually is, and does so in a way that always answers.
 *
 * Asking GPS for a fresh fix indoors returns nothing for as long as you are
 * willing to wait, and a button wired straight to that appeared dead. So this
 * prefers fused and network, hands back the most recent cached fix at once so
 * something visible happens, upgrades it if a fresh one arrives, and gives up
 * after a bounded time rather than holding the radio awake.
 *
 * Permissions are the caller's business: this assumes they are held.
 */
class DeviceLocator(private val context: Context) {

    interface Callback {
        /** No location provider is enabled at all. */
        fun onProvidersOff()

        /** A position — possibly cached, possibly fresh, in that order. */
        fun onFix(location: Location)

        /** Nothing cached and nothing fresh within the timeout. */
        fun onNothing()
    }

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun locate(callback: Callback) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            callback.onProvidersOff()
            return
        }

        val cached = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null) callback.onFix(cached)

        val signal = CancellationSignal()
        var delivered = false
        lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { location ->
            delivered = true
            when {
                location != null -> callback.onFix(location)
                cached == null -> callback.onNothing()
            }
        }
        // getCurrentLocation has no timeout of its own, and an outstanding
        // request holds the radio awake.
        handler.postDelayed({
            if (!delivered) {
                signal.cancel()
                if (cached == null) callback.onNothing()
            }
        }, FIX_TIMEOUT_MS)
    }

    private companion object {
        /** How long to wait for a fresh fix before giving up on the radio. */
        const val FIX_TIMEOUT_MS = 15_000L
    }
}
