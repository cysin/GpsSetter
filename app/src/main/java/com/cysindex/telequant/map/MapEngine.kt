package com.cysindex.telequant.map

import android.content.Context
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.utils.PrefManager
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * MapLibre initialisation: styles, the HTTP stack, and the tile proxy.
 *
 * Must run before any MapView is inflated — [MapLibre.getInstance] is what
 * brings the native renderer up.
 */
object MapEngine {

    /**
     * OpenFreeMap, chosen because its terms actually permit what this app does.
     * It requires no API key, states "there are no limits on the number of map
     * views or requests", and allows commercial use.
     *
     * This matters for the offline feature specifically: the OSM Foundation's
     * tile policy bans pre-fetching outright ("Offline use is not permitted on
     * tile.openstreetmap.org"), so downloading a region from the standard OSM
     * tile server — which the previous osmdroid implementation did — would get
     * the client blocked.
     */
    const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"
    const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"

    @Volatile
    private var initialised = false

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        applyHttpStack()
        MapLibre.getInstance(context.applicationContext)
        initialised = true
        applyConnectivity()
    }

    /**
     * Forces MapLibre to treat the device as offline, so it serves only what a
     * downloaded region already holds and never spends data on tiles. Without
     * this the renderer happily fetches whatever is missing from the region.
     */
    fun applyConnectivity() {
        if (!initialised) return
        runCatching {
            if (PrefManager.offlineMap) {
                MapLibre.setConnected(false)
            } else {
                // null hands connectivity back to the system's own state rather
                // than pinning it to "connected".
                MapLibre.setConnected(null)
            }
        }.onFailure { Timber.tag(TAG).w(it, "connectivity override") }
    }

    fun styleUrl(): String = when (PrefManager.mapStyle) {
        "bright" -> STYLE_BRIGHT
        "positron" -> STYLE_POSITRON
        else -> STYLE_LIBERTY
    }

    /**
     * MapLibre fetches through OkHttp rather than HttpURLConnection, so the
     * proxy is installed by replacing its client — osmdroid's
     * Configuration.setHttpProxy() has no equivalent here.
     *
     * The proxy must speak HTTP and support CONNECT: the tile endpoints are
     * HTTPS, and the hop to the proxy itself is plaintext. That is why the
     * setting is a host and port with no scheme.
     */
    fun applyHttpStack() {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // The OSM ecosystem asks for a User-Agent that identifies the
                // app and offers a way to make contact, so a misbehaving client
                // can be reached rather than simply blocked.
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }

        if (PrefManager.tileProxyEnabled) {
            val host = PrefManager.tileProxyHost.orEmpty().ifBlank { TileDefaults.HOST }
            val port = PrefManager.tileProxyPort?.toIntOrNull() ?: TileDefaults.PORT
            runCatching {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            }.onFailure { Timber.tag(TAG).w(it, "proxy $host:$port") }
        }

        runCatching { HttpRequestUtil.setOkHttpClient(builder.build()) }
            .onFailure { Timber.tag(TAG).w(it, "http stack") }
    }

    const val USER_AGENT =
        "TeleQuant/${BuildConfig.VERSION_NAME} (+https://github.com/cysin/GpsSetter)"

    object TileDefaults {
        const val HOST = "127.0.0.1"
        const val PORT = 33009
    }

    private const val TAG = "TeleQuantMap"
}
