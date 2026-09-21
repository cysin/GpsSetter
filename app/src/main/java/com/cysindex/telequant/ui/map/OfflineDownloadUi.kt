package com.cysindex.telequant.ui.map

import android.app.Activity
import com.cysindex.telequant.R
import com.cysindex.telequant.map.OfflineRegions
import com.cysindex.telequant.utils.ext.showToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Offers to download the current viewport for offline use, and shows progress.
 *
 * This is legitimate only because the tiles come from OpenFreeMap, which places
 * no limits on requests. The OSM Foundation's policy bans pre-fetching from
 * tile.openstreetmap.org outright, so the previous raster implementation would
 * have got the client blocked.
 */
class OfflineDownloadUi(private val activity: Activity) {

    fun offer(map: MapLibreMap) {
        val bounds = map.projection.visibleRegion.latLngBounds
        val minZoom = map.cameraPosition.zoom.coerceAtLeast(1.0)
        val maxZoom = (minZoom + EXTRA_ZOOM).coerceAtMost(16.0)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_download)
            .setMessage(activity.getString(R.string.offline_confirm, minZoom.toInt(), maxZoom.toInt()))
            .setPositiveButton(R.string.offline_start) { _, _ -> run(bounds, minZoom, maxZoom) }
            .setNeutralButton(R.string.offline_manage) { _, _ -> manage() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Lists what has been downloaded and lets it be deleted. Without this every
     * download accumulated forever, with no way to see how much was there or
     * to get the space back.
     */
    fun manage() {
        val regions = OfflineRegions(activity)
        regions.list { list ->
            if (list.isEmpty()) {
                activity.showToast(activity.getString(R.string.offline_none))
                return@list
            }
            val names = list.map { regions.nameOf(it) }.toTypedArray()
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.offline_manage)
                .setItems(names) { _, index ->
                    val region = list[index]
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(names[index])
                        .setMessage(R.string.offline_delete_confirm)
                        .setPositiveButton(R.string.offline_delete) { _, _ ->
                            regions.delete(region) { error ->
                                activity.showToast(
                                    if (error == null) activity.getString(R.string.offline_deleted)
                                    else activity.getString(R.string.offline_failed, error)
                                )
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun run(bounds: LatLngBounds, minZoom: Double, maxZoom: Double) {
        var handle: OfflineRegions.Handle? = null
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_downloading)
            .setMessage(activity.getString(R.string.offline_progress_pct, 0))
            .setCancelable(false)
            // A modal with no way out is a hung app if the download stalls.
            .setNegativeButton(android.R.string.cancel) { _, _ -> handle?.cancel() }
            .show()

        handle = OfflineRegions(activity).download(
            name = "%.4f,%.4f".format(bounds.center.latitude, bounds.center.longitude),
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            pixelRatio = activity.resources.displayMetrics.density,
            onProgress = {
                progress.setMessage(
                    activity.getString(R.string.offline_progress_pct, (it.fraction * 100).toInt())
                )
            },
            onComplete = {
                progress.dismiss()
                activity.showToast(activity.getString(R.string.offline_done))
            },
            onError = { reason ->
                progress.dismiss()
                activity.showToast(activity.getString(R.string.offline_failed, reason))
            }
        )
    }

    private companion object {
        /** Zoom levels beyond the current one to also fetch when going offline. */
        const val EXTRA_ZOOM = 3.0
    }
}
