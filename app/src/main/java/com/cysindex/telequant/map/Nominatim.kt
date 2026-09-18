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
 * Proxied independently of the tiles. On the network this was measured
 * against, the tile host answers directly in well under a second while
 * Nominatim does not answer at all — which is why the two are separate
 * switches rather than one.
 */
object Nominatim {

    data class Place(val displayName: String, val lat: Double, val lon: Double)

    /**
     * Rebuilt whenever the proxy settings change rather than built once.
     *
     * A `by lazy` client meant a changed proxy did nothing until the app was
     * killed and reopened, with nothing on screen to say so — turning the
     * setting on and watching search keep failing looks exactly like the
     * setting being broken.
     */
    @Volatile
    private var cached: Pair<String, OkHttpClient>? = null

    private fun client(): OkHttpClient {
        val enabled = PrefManager.proxyGeocoder
        val host = PrefManager.proxyHost.orEmpty().ifBlank { MapEngine.TileDefaults.HOST }
        val port = PrefManager.proxyPort?.toIntOrNull() ?: MapEngine.TileDefaults.PORT
        val key = "$enabled|$host|$port"

        cached?.let { (cachedKey, cachedClient) -> if (cachedKey == key) return cachedClient }

        val builder = OkHttpClient.Builder()
            // Short, because this runs while someone waits for a search box to
            // answer. A blocked host otherwise burns the full connect timeout
            // twice over, since OkHttp retries.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (enabled) {
            runCatching {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            }.onFailure { Timber.tag(TAG).w(it, "proxy $host:$port") }
        }
        return builder.build().also { cached = key to it }
    }

    /**
     * Nominatim's usage policy requires an identifying User-Agent and caps
     * traffic at roughly one request per second; this app only ever queries on
     * an explicit search, so the rate is not a concern.
     */
    /**
     * @return the matches, or null when the geocoder could not be reached.
     *   An empty list and an unreachable server used to be the same value, so
     *   the UI reported "address not found" for a network that was simply
     *   blocked — which sends the user looking for a different spelling.
     */
    suspend fun search(query: String, limit: Int = 5): List<Place>? = withContext(Dispatchers.IO) {
        val url = "$BASE/search?format=jsonv2&limit=$limit&q=${query.urlEncoded()}"
        request(url)?.let { parseList(it) }
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
        client().newCall(request).execute().use { response ->
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
