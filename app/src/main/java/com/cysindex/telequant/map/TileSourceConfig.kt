package com.cysindex.telequant.map

import android.content.Context
import com.cysindex.telequant.utils.PrefManager
import org.osmdroid.config.Configuration
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * osmdroid setup: where tiles are cached and how they are fetched.
 *
 * Applied once at application start so the configuration is in place before any
 * MapView exists — osmdroid reads several of these values when a tile provider
 * is constructed, not per request.
 */
object TileSourceConfig {

    fun apply(context: Context) {
        val config = Configuration.getInstance()

        runCatching {
            config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        }

        // OSM's tile policy requires a distinctive agent; the default is refused.
        config.userAgentValue = context.packageName

        // App-specific external storage: no permission needed on API 30+, and
        // it is removed with the app rather than left behind.
        runCatching {
            context.getExternalFilesDir(null)?.let { base ->
                config.osmdroidBasePath = base
                config.osmdroidTileCache = base.resolve("tiles").apply { mkdirs() }
            }
        }.onFailure { Timber.tag(TAG).w(it, "tile cache path") }

        config.tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
        config.tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
        config.tileDownloadThreads = 4

        applyProxy()
    }

    /**
     * Routes tile downloads through a local HTTP proxy.
     *
     * osmdroid fetches over [java.net.HttpURLConnection], which reaches an HTTPS
     * tile server through a proxy by issuing CONNECT. That means the configured
     * endpoint has to be an HTTP proxy that supports CONNECT — pointing this at
     * an HTTPS listener does not work, because the connection to the proxy
     * itself is plain HTTP.
     */
    fun applyProxy() {
        val config = Configuration.getInstance()
        if (!PrefManager.tileProxyEnabled) {
            config.httpProxy = null
            return
        }
        val host = PrefManager.tileProxyHost.orEmpty().ifBlank { DEFAULT_HOST }
        val port = PrefManager.tileProxyPort?.toIntOrNull() ?: DEFAULT_PORT
        config.httpProxy = runCatching {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }.onFailure { Timber.tag(TAG).w(it, "proxy $host:$port") }.getOrNull()
    }

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 33009

    private const val TAG = "TeleQuantTiles"
    private const val CACHE_MAX_BYTES = 1024L * 1024L * 1024L   // 1 GiB
    private const val CACHE_TRIM_BYTES = 800L * 1024L * 1024L
}
