package com.cysindex.telequant.map

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import timber.log.Timber
import org.json.JSONObject

/**
 * Downloads map regions for offline use through MapLibre's own offline store.
 *
 * Unlike the previous raster approach this is legitimate: OpenFreeMap places no
 * limits on requests, whereas the OSM Foundation's policy bans pre-fetching
 * from tile.openstreetmap.org entirely.
 *
 * Vector tiles also make the download far smaller than the raster equivalent,
 * and the same region serves every zoom level within its range instead of
 * needing each one fetched separately.
 */
class OfflineRegions(context: Context) {

    private val manager = OfflineManager.getInstance(context.applicationContext)

    data class Progress(val completed: Long, val required: Long, val bytes: Long) {
        val fraction: Double
            get() = if (required > 0) completed.toDouble() / required else 0.0
    }

    /** A download in flight. [cancel] stops it and discards the partial region. */
    class Handle internal constructor() {
        @Volatile
        internal var region: OfflineRegion? = null

        @Volatile
        internal var cancelled = false

        fun cancel() {
            cancelled = true
            val r = region ?: return
            r.setObserver(null)
            r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            // A half-downloaded region is not usable offline and would still
            // show up in the list, so it goes with the cancellation.
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = Unit
                override fun onError(error: String) {
                    Timber.tag(TAG).w("delete after cancel: $error")
                }
            })
        }
    }

    fun download(
        name: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        pixelRatio: Float,
        onProgress: (Progress) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): Handle {
        val handle = Handle()
        val definition = OfflineTilePyramidRegionDefinition(
            MapEngine.styleUrl(),
            bounds,
            minZoom,
            maxZoom,
            pixelRatio
        )

        manager.createOfflineRegion(
            definition,
            JSONObject().put("name", name).toString().toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    handle.region = offlineRegion
                    // Cancelled before the region even existed: clean it up
                    // now rather than leaving an empty region behind.
                    if (handle.cancelled) {
                        handle.cancel()
                        return
                    }
                    observe(offlineRegion, onProgress, onComplete, onError)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) = onError(error)
            }
        )
        return handle
    }

    /** The stored name, or the id when the metadata is unreadable. */
    fun nameOf(region: OfflineRegion): String = runCatching {
        JSONObject(String(region.metadata)).optString("name")
    }.getOrNull()?.ifBlank { null } ?: "#${region.id}"

    fun delete(region: OfflineRegion, onDone: (String?) -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone(null)
            override fun onError(error: String) = onDone(error)
        })
    }

    private fun observe(
        region: OfflineRegion,
        onProgress: (Progress) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            private var finished = false

            override fun onStatusChanged(status: OfflineRegionStatus) {
                onProgress(
                    Progress(
                        completed = status.completedResourceCount,
                        required = status.requiredResourceCount,
                        bytes = status.completedResourceSize
                    )
                )
                if (status.isComplete && !finished) {
                    finished = true
                    // Stop the downloader before handing control back, or it
                    // keeps an observer and a database handle alive.
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    onComplete()
                }
            }

            override fun onError(error: OfflineRegionError) {
                Timber.tag(TAG).w("offline region: ${error.reason} ${error.message}")
                onError(error.message)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                onError("tile limit $limit exceeded")
            }
        })
    }

    fun list(onResult: (List<OfflineRegion>) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                onResult(offlineRegions?.toList().orEmpty())
            }

            override fun onError(error: String) {
                Timber.tag(TAG).w("list offline regions: $error")
                onResult(emptyList())
            }
        })
    }

    fun deleteAll(onDone: () -> Unit) {
        list { regions ->
            if (regions.isEmpty()) {
                onDone()
                return@list
            }
            var remaining = regions.size
            regions.forEach { region ->
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        if (--remaining == 0) onDone()
                    }

                    override fun onError(error: String) {
                        Timber.tag(TAG).w("delete region: $error")
                        if (--remaining == 0) onDone()
                    }
                })
            }
        }
    }

    private companion object {
        const val TAG = "TeleQuantMap"
    }
}
