package com.cysindex.telequant.map

import com.cysindex.telequant.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Place search and reverse geocoding against Nominatim.
 *
 * Replaces [android.location.Geocoder], which is backed by Google Play Services
 * and returns nothing on devices without them — including most devices in
 * mainland China. The previous code handled the empty result gracefully, so
 * search did not crash; it simply never found anything.
 *
 * Nominatim is plain HTTPS, so it reaches the network through the same proxy
 * the map tiles use.
 */
object Nominatim {

    data class Place(val displayName: String, val lat: Double, val lon: Double)

    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)

        if (PrefManager.tileProxyEnabled) {
            val host = PrefManager.tileProxyHost.orEmpty()
                .ifBlank { MapEngine.TileDefaults.HOST }
            val port = PrefManager.tileProxyPort?.toIntOrNull() ?: MapEngine.TileDefaults.PORT
            runCatching {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            }
        }
        return builder.build()
    }

    /**
     * Nominatim's usage policy requires an identifying User-Agent and caps
     * traffic at roughly one request per second; this app only ever queries on
     * an explicit search, so the rate is not a concern.
     */
    suspend fun search(query: String, limit: Int = 5): List<Place> = withContext(Dispatchers.IO) {
        val url = "$BASE/search?format=jsonv2&limit=$limit&q=${query.urlEncoded()}"
        request(url)?.let { parseList(it) }.orEmpty()
    }

    suspend fun reverse(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/reverse?format=jsonv2&lat=$lat&lon=$lon"
        val body = request(url) ?: return@withContext null
        runCatching {
            org.json.JSONObject(body).optString("display_name").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun request(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MapEngine.USER_AGENT)
            .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.onFailure { Timber.tag(TAG).d(it, "nominatim request failed") }.getOrNull()

    private fun parseList(body: String): List<Place> = runCatching {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            Place(o.optString("display_name"), lat, lon)
        }
    }.getOrDefault(emptyList())

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private const val BASE = "https://nominatim.openstreetmap.org"
    private const val TAG = "TeleQuantMap"
}
