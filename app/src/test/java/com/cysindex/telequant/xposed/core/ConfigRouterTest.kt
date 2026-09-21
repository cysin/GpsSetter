package com.cysindex.telequant.xposed.core

import com.cysindex.telequant.xposed.core.source.ConfigRouter
import com.cysindex.telequant.xposed.core.source.ConfigSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The same APK runs under a framework that hands the settings over as a file
 * and one that does not, so which route is in use is a runtime question. These
 * are the answers it must give.
 */
class ConfigRouterTest {

    private class FakeSource(override val name: String) : ConfigSource {
        var answer: Config? = null
        var reads = 0
        override fun read(): Config? {
            reads++
            return answer
        }
    }

    private val file = FakeSource("file")
    private val provider = FakeSource("provider")
    private val cache = FakeSource("cache")

    private var now = 0L
    private val log = mutableListOf<String>()

    private val router = ConfigRouter(
        sources = listOf(file, provider, cache),
        clock = { now },
        log = { log += it }
    )

    private fun config(version: Long) = Config.DEFAULT.copy(started = true, version = version)

    @Test
    fun `the first route that answers is used`() {
        file.answer = config(1)
        provider.answer = config(2)
        assertEquals(config(1), router.read()?.second)
        assertSame(file, router.active)
    }

    @Test
    fun `a route that cannot answer is skipped`() {
        // The rootless case: the preferences file is there but belongs to
        // another uid, so it reads as absent rather than failing.
        provider.answer = config(2)
        assertEquals(config(2), router.read()?.second)
        assertSame(provider, router.active)
    }

    @Test
    fun `nothing available means spoofing off, not a stale value`() {
        assertNull(router.read())
    }

    @Test
    fun `the chosen route is the only one read afterwards`() {
        file.answer = config(1)
        repeat(50) { router.read() }
        assertEquals(50, file.reads)
        assertEquals(0, provider.reads)
        assertEquals(0, cache.reads)
    }

    @Test
    fun `a failing route is replaced immediately`() {
        file.answer = config(1)
        router.read()
        file.answer = null
        provider.answer = config(2)
        assertEquals(config(2), router.read()?.second)
        assertSame(provider, router.active)
    }

    @Test
    fun `a fallback route is reconsidered on a timer, not on every call`() {
        // The module App was not running when this process started; once it is,
        // the cached copy should stop being used — but looking for it per fix
        // would mean a binder call on a path that runs per fix.
        cache.answer = config(1)
        router.read()
        assertSame(cache, router.active)

        provider.answer = config(2)
        repeat(10) { router.read() }
        assertSame("looked again too eagerly", cache, router.active)

        now += 60_000
        assertEquals(config(2), router.read()?.second)
        assertSame(provider, router.active)
    }

    @Test
    fun `the first route is never re-scanned, since nothing outranks it`() {
        file.answer = config(1)
        router.read()
        now += 600_000
        repeat(5) { router.read() }
        assertEquals(0, provider.reads)
    }

    @Test
    fun `the route in use is named once, not per read`() {
        file.answer = config(1)
        repeat(20) { router.read() }
        assertEquals(listOf("settings read through file"), log)
    }
}
