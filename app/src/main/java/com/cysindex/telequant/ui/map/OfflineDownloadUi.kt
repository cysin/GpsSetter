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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun run(bounds: LatLngBounds, minZoom: Double, maxZoom: Double) {
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_downloading)
            .setMessage(activity.getString(R.string.offline_progress_pct, 0))
            .setCancelable(false)
            .show()

        OfflineRegions(activity).download(
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
