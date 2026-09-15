package com.cysindex.telequant.xposed.core

import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Feeds synthetic location updates to listeners that registered for them.
 *
 * Needed because the position hooks alone only rewrite updates that the system
 * actually delivers: with no real fix (indoors, GPS off, airplane mode) an app
 * would simply receive nothing.
 *
 * The previous approach spawned a bare [Thread] per listener, which produced
 * three separate defects:
 *
 *  - callbacks ran on that thread, ignoring the Looper/Executor the app asked
 *    for, so any listener touching UI hit CalledFromWrongThreadException;
 *  - `removeUpdates` called `Thread.join(2000)` on the caller's thread — the
 *    main thread — while the worker was mid-`sleep`, stalling the UI for up to
 *    two seconds per listener;
 *  - an app that never calls `removeUpdates` (common) leaked the thread and the
 *    listener reference for the life of the process.
 *
 * One shared daemon scheduler replaces all of it. Delivery is posted to the
 * target the app supplied, cancellation is a [ScheduledFuture.cancel] with
 * nothing to join, and registrations are keyed by listener identity.
 */
object LocationDispatcher {

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "telequant-location").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    private class Registration(
        val provider: String,
        val target: DeliveryTarget,
        val onLocation: (Location) -> Unit,
        var future: ScheduledFuture<*>? = null
    )

    /** Where a callback must run, as chosen by the app at registration time. */
    class DeliveryTarget private constructor(
        private val handler: Handler?,
        private val executor: Executor?
    ) {
        fun post(block: () -> Unit) {
            val exec = executor
            val h = handler
            when {
                exec != null -> runCatching { exec.execute(block) }
                h != null -> h.post(block)
                else -> block()
            }
        }

        companion object {
            fun of(looper: Looper?): DeliveryTarget =
                DeliveryTarget(Handler(looper ?: Looper.getMainLooper()), null)

            fun of(executor: Executor?): DeliveryTarget =
                if (executor != null) DeliveryTarget(null, executor) else of(null as Looper?)
        }
    }

    private val registrations = ConcurrentHashMap<Any, Registration>()

    fun register(
        key: Any,
        provider: String?,
        intervalMillis: Long,
        target: DeliveryTarget,
        onLocation: (Location) -> Unit
    ) {
        if (registrations.containsKey(key)) return

        val registration = Registration(
            provider = provider ?: LocationManager.GPS_PROVIDER,
            target = target,
            onLocation = onLocation
        )
        registrations[key] = registration

        // Honour the app's requested cadence, but keep it sane: a request for
        // 0 ms would otherwise busy-loop the scheduler.
        val period = intervalMillis.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        registration.future = scheduler.scheduleWithFixedDelay(
            {
                runCatching {
                    if (!SpoofEngine.isEnabled) return@runCatching
                    val location = LocationFactory.build(registration.provider)
                    registration.target.post { registration.onLocation(location) }
                }
            },
            INITIAL_DELAY_MS, period, TimeUnit.MILLISECONDS
        )
    }

    fun unregister(key: Any) {
        registrations.remove(key)?.future?.cancel(false)
    }

    fun isRegistered(key: Any) = registrations.containsKey(key)

    private const val MIN_INTERVAL_MS = 400L
    private const val MAX_INTERVAL_MS = 10_000L
    private const val INITIAL_DELAY_MS = 150L
}
