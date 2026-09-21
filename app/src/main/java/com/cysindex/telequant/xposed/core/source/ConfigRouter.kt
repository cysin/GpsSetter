package com.cysindex.telequant.xposed.core.source

import com.cysindex.telequant.xposed.core.Config

/**
 * Picks the route the settings arrive by, and sticks to it.
 *
 * The rule is "the first one that answers, until it stops answering". What
 * makes it more than a loop is that the answer has to stay cheap on a path a
 * hook calls per fix: once a route is chosen, a read touches only that one,
 * and a route that has failed is not retried on every call. A route below the
 * first is reconsidered on a timer instead, because the reason it was chosen —
 * a module App that was not running, a file not yet written — usually goes
 * away on its own.
 */
internal class ConfigRouter(
    private val sources: List<ConfigSource>,
    private val clock: () -> Long,
    private val log: (String) -> Unit
) {

    var active: ConfigSource? = null
        private set

    private var rescanAtMillis = 0L

    /** The settings and the route that produced them, or null when none can. */
    fun read(): Pair<ConfigSource, Config>? {
        val current = active
        if (current != null && !shouldRescan(current)) {
            current.read()?.let { return current to it }
        }
        return select()
    }

    private fun shouldRescan(current: ConfigSource) =
        current !== sources.first() && clock() >= rescanAtMillis

    @Synchronized
    private fun select(): Pair<ConfigSource, Config>? {
        rescanAtMillis = clock() + RESCAN_INTERVAL_MILLIS
        sources.forEach { source ->
            val config = source.read() ?: return@forEach
            if (source !== active) {
                active = source
                log("settings read through ${source.name}")
            }
            return source to config
        }
        if (active != null) {
            log("no settings source left; this process stops spoofing")
            active = null
        }
        return null
    }

    private companion object {
        const val RESCAN_INTERVAL_MILLIS = 60_000L
    }
}
