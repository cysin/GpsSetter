package com.cysindex.telequant.xposed.core

import com.cysindex.telequant.config.ConfigKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings reach a hooked process by three routes; [Config.from] is the one
 * place that decides what each key means, so this is where the defaults and the
 * legacy keys are pinned down.
 */
class ConfigTest {

    private class MapReader(private val values: Map<String, Any>) : ConfigReader {
        override fun contains(key: String) = values.containsKey(key)
        override fun bool(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
        override fun long(key: String, fallback: Long) = values[key] as? Long ?: fallback
        override fun float(key: String, fallback: Float) = values[key] as? Float ?: fallback
        override fun string(key: String) = values[key] as? String
    }

    private fun read(vararg pairs: Pair<String, Any>) = Config.from(MapReader(pairs.toMap()))

    @Test
    fun `an empty store reads as spoofing off`() {
        // A store that cannot be read at all must not put the device in New
        // York: absent settings mean the module does nothing.
        val config = read()
        assertFalse(config.started)
        assertEquals(Config.DEFAULT, config)
    }

    @Test
    fun `coordinates round-trip through raw bits`() {
        val config = read(
            ConfigKeys.LAT to 31.228746.toRawBits(),
            ConfigKeys.LNG to 121.477905.toRawBits()
        )
        assertEquals(31.228746, config.lat, 0.0)
        assertEquals(121.477905, config.lng, 0.0)
    }

    @Test
    fun `a coordinate written by an older build is still read`() {
        // Builds before the double keys wrote floats. Ignoring them would move
        // the anchor to the default on the first launch after an update.
        val config = read(ConfigKeys.LAT_FLOAT to 31.2287f, ConfigKeys.LNG_FLOAT to 121.4779f)
        assertEquals(31.2287, config.lat, 1e-4)
        assertEquals(121.4779, config.lng, 1e-4)
    }

    @Test
    fun `the double key wins over the float one`() {
        val config = read(
            ConfigKeys.LAT to 31.228746.toRawBits(),
            ConfigKeys.LAT_FLOAT to 1.0f
        )
        assertEquals(31.228746, config.lat, 0.0)
    }

    @Test
    fun `numbers typed into a text preference are parsed, and nonsense falls back`() {
        assertEquals(25f, read(ConfigKeys.ACCURACY to " 25 ").accuracy, 0f)
        assertEquals(3.5, read(ConfigKeys.JITTER_RADIUS to "3.5").jitterRadiusMeters, 0.0)
        assertEquals(10f, read(ConfigKeys.ACCURACY to "").accuracy, 0f)
        assertEquals(10.0, read(ConfigKeys.JITTER_RADIUS to "far").jitterRadiusMeters, 0.0)
    }

    @Test
    fun `the per-signal switches default to on, except the time zone`() {
        val config = read()
        assertTrue(config.spoofCell)
        assertTrue(config.spoofWifi)
        assertTrue(config.spoofBluetooth)
        assertFalse(config.spoofTimeZone)
        assertFalse(config.gcj02Output)
    }

    @Test
    fun `an environment is carried as its document, unparsed`() {
        assertNull(read().environmentJson)
        assertEquals("{}", read(ConfigKeys.ENVIRONMENT to "{}").environmentJson)
    }

    @Test
    fun `the version is what tells two copies of the settings apart`() {
        assertEquals(0L, read().version)
        assertEquals(42L, read(ConfigKeys.VERSION to 42L).version)
    }
}
