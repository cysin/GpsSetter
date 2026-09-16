package com.cysindex.telequant.xposed.core

import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
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

    /**
     * Holds the listener weakly. A strong reference here would outlive the app's
     * own — the scheduled task keeps the Registration alive indefinitely — so an
     * app that never calls removeUpdates (which is common) would pin its
     * listener, and whatever that listener captures, for the life of the
     * process. The task drops itself once the referent is collected.
     */
    private class Registration(
        val provider: String,
        val target: DeliveryTarget,
        listener: Any,
        val deliver: (Any, Location) -> Unit,
        var future: ScheduledFuture<*>? = null
    ) {
        private val listenerRef = WeakReference(listener)
        fun listener(): Any? = listenerRef.get()
    }

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

    /**
     * Keyed by identity hash rather than by the listener itself: a map key is a
     * strong reference, and so is anything the scheduled task closes over, so
     * keying by the object would pin it no matter how weakly Registration holds
     * it. An identity-hash collision merely means the second listener gets no
     * synthetic updates — real ones are still rewritten by its proxy.
     */
    private val registrations = ConcurrentHashMap<Int, Registration>()

    private fun keyOf(listener: Any) = System.identityHashCode(listener)

    fun register(
        listener: Any,
        provider: String?,
        intervalMillis: Long,
        target: DeliveryTarget,
        deliver: (Any, Location) -> Unit
    ) {
        val key = keyOf(listener)
        if (registrations.containsKey(key)) return

        val registration = Registration(
            provider = provider ?: LocationManager.GPS_PROVIDER,
            target = target,
            listener = listener,
            deliver = deliver
        )
        registrations[key] = registration

        // Honour the app's requested cadence, but keep it sane: a request for
        // 0 ms would otherwise busy-loop the scheduler.
        val period = intervalMillis.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        registration.future = scheduler.scheduleWithFixedDelay(
            {
                runCatching {
                    // The listener is held weakly, so an app that never calls
                    // removeUpdates does not pin it forever. Once it has been
                    // collected there is nobody left to notify, so the task
                    // retires itself.
                    val listener = registration.listener()
                    if (listener == null) {
                        registrations.remove(key)
                        registration.future?.cancel(false)
                        return@runCatching
                    }
                    if (!SpoofEngine.isEnabled) return@runCatching
                    val location = LocationFactory.build(registration.provider)
                    registration.target.post { registration.deliver(listener, location) }
                }
            },
            INITIAL_DELAY_MS, period, TimeUnit.MILLISECONDS
        )
    }

    fun unregister(listener: Any) {
        registrations.remove(keyOf(listener))?.future?.cancel(false)
    }

    private const val MIN_INTERVAL_MS = 400L
    private const val MAX_INTERVAL_MS = 10_000L
    private const val INITIAL_DELAY_MS = 150L
}
