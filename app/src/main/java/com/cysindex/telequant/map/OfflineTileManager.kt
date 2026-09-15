package com.cysindex.telequant.map

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import timber.log.Timber
import java.io.File

/**
 * Downloads a map region for offline use and reports on the tile cache.
 */
class OfflineTileManager(private val map: MapView) {

    data class Estimate(val tileCount: Int, val approxBytes: Long)

    /**
     * Tiles grow fourfold per zoom level, so an innocuous-looking range can run
     * to hundreds of megabytes. Always show this before starting a download.
     */
    fun estimate(area: BoundingBox, zoomMin: Int, zoomMax: Int): Estimate {
        val manager = CacheManager(map)
        val count = runCatching {
            manager.possibleTilesInArea(area, zoomMin, zoomMax)
        }.getOrDefault(0)
        return Estimate(count, count.toLong() * AVERAGE_TILE_BYTES)
    }

    fun download(
        context: Context,
        area: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinished: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val manager = runCatching { CacheManager(map) }.getOrElse {
            onFailed(it.message ?: "cache unavailable")
            return
        }

        runCatching {
            manager.downloadAreaAsyncNoUI(
                context,
                area,
                zoomMin,
                zoomMax,
                object : CacheManager.CacheManagerCallback {
                    private var total = 0

                    override fun onTaskComplete() = onFinished()

                    override fun onTaskFailed(errors: Int) {
                        onFailed("$errors tiles failed")
                    }

                    override fun updateProgress(
                        progress: Int,
                        currentZoomLevel: Int,
                        zoomMin: Int,
                        zoomMax: Int
                    ) {
                        onProgress(progress, total)
                    }

                    override fun downloadStarted() = Unit

                    override fun setPossibleTilesInArea(total: Int) {
                        this.total = total
                    }
                }
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "download")
            onFailed(it.message ?: "download failed")
        }
    }

    companion object {
        private const val TAG = "TeleQuantTiles"

        /** Rough average for a 256px PNG tile; only used for the size estimate. */
        private const val AVERAGE_TILE_BYTES = 12_000L

        fun cacheSizeBytes(): Long = runCatching {
            Configuration.getInstance().osmdroidTileCache?.walkBottomUp()
                ?.filter { it.isFile }
                ?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)

        fun clearCache(): Boolean = runCatching {
            val cache: File = Configuration.getInstance().osmdroidTileCache ?: return false
            cache.listFiles()?.forEach { it.deleteRecursively() }
            true
        }.getOrDefault(false)
    }
}
