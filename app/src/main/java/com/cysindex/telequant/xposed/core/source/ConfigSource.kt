package com.cysindex.telequant.xposed.core.source

import com.cysindex.telequant.xposed.core.Config

/**
 * One route by which the settings reach a hooked process.
 *
 * Implementations are asked for a value far more often than it can change — a
 * hot hook like `Location.getLatitude` reaches this through SpoofEngine — so
 * [read] must be cheap. Each one therefore holds its own copy and decides for
 * itself when that copy is stale: a file change for preferences, a content
 * observer for the provider, a modification time for the cached copy. None of
 * them may do real work per call.
 */
internal interface ConfigSource {

    /** Named in the log so the route in use is visible when diagnosing. */
    val name: String

    /** The settings, or null when this route is not usable in this process. */
    fun read(): Config?
}
