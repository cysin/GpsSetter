package com.cysindex.telequant.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import com.cysindex.telequant.R
import com.cysindex.telequant.spoof.JitterEngine
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The three markers and the jitter ring, as MapLibre style layers.
 *
 * Core style layers rather than the annotation plugin: the newest plugin
 * (3.0.2) is built against MapLibre 11.3.0 and would be force-upgraded two
 * major versions to 13.6.1 here. That compiles but could break at runtime,
 * which is the one thing that cannot be checked from a build machine.
 *
 * Three markers, because they answer three different questions. The selection
 * is where the next Start would put you; the anchor is where apps are being
 * told you are; the live dot is the exact fix being handed out this instant,
 * wandering inside the ring. They coincide often enough that one marker could
 * not say which it meant the moment they stopped.
 *
 * This class owns the drawing only. What to draw — the selection, whether a
 * simulation is running, the radius — is the activity's state and arrives as
 * arguments.
 */
class MapMarkers(private val context: Context, private val accent: Int) {

    /** A position as (latitude, longitude). */
    data class LatLon(val lat: Double, val lon: Double)

    fun install(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_SELECTION))
        style.addSource(GeoJsonSource(SOURCE_ACTIVE))
        style.addSource(GeoJsonSource(SOURCE_LIVE))
        style.addSource(GeoJsonSource(SOURCE_CANDIDATE))
        style.addSource(GeoJsonSource(SOURCE_JITTER))

        // The jitter ring belongs to the active marker: it is the area apps are
        // actually being given, not a property of a place under consideration.
        style.addLayer(
            FillLayer(LAYER_JITTER_FILL, SOURCE_JITTER).withProperties(
                PropertyFactory.fillColor(COLOR_ACTIVE),
                PropertyFactory.fillOpacity(0.16f)
            )
        )
        style.addLayer(
            LineLayer(LAYER_JITTER_LINE, SOURCE_JITTER).withProperties(
                PropertyFactory.lineColor(COLOR_ACTIVE),
                PropertyFactory.lineWidth(1.5f)
            )
        )

        // Icons rather than CircleLayer: a CircleLayer on these same sources
        // draws nothing here — verified with a 40 px magenta circle that never
        // appeared while the fill and line layers above it rendered fine. The
        // style's own POI markers are symbol icons, which is the path that
        // demonstrably works.
        style.addImage(IMAGE_SELECTION, markerPin(Color.WHITE, accent))
        style.addImage(IMAGE_ACTIVE, markerDot(COLOR_ACTIVE))
        style.addImage(IMAGE_LIVE, liveDot(COLOR_ACTIVE))

        // Different shape *and* different colour. One of the two alone survives
        // neither a colour-blind user nor a greyscale screenshot.
        style.addLayer(
            SymbolLayer(LAYER_SELECTION, SOURCE_SELECTION).withProperties(
                PropertyFactory.iconImage(IMAGE_SELECTION),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textFont(MAP_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textColor(accent),
                PropertyFactory.textAllowOverlap(true),
                // The icon is the marker; losing the glyphs must not lose it.
                PropertyFactory.textOptional(true)
            )
        )
        style.addLayer(
            SymbolLayer(LAYER_ACTIVE, SOURCE_ACTIVE).withProperties(
                PropertyFactory.iconImage(IMAGE_ACTIVE),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textFont(MAP_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textColor(COLOR_ACTIVE),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textOptional(true)
            )
        )

        // A search result awaiting confirmation: the selection pin at half
        // opacity, so it reads as "proposed" next to the committed one.
        style.addLayer(
            SymbolLayer(LAYER_CANDIDATE, SOURCE_CANDIDATE).withProperties(
                PropertyFactory.iconImage(IMAGE_SELECTION),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOpacity(0.55f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textFont(MAP_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textColor(accent),
                PropertyFactory.textOpacity(0.7f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textOptional(true)
            )
        )

        // The wandering fix itself. Drawn above the anchor so it stays readable
        // when the two coincide, which they do whenever the radius is 0.
        style.addLayer(
            SymbolLayer(LAYER_LIVE, SOURCE_LIVE).withProperties(
                PropertyFactory.iconImage(IMAGE_LIVE),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    /**
     * Redraws the selection, the anchor and the jitter ring.
     *
     * @param active the anchor being simulated, or null when stopped — the
     *   marker the user asked to appear only while something is running.
     */
    fun draw(
        style: Style,
        selection: LatLon,
        selectionLabel: String,
        active: LatLon?,
        radiusMetres: Double
    ) {
        val selectionSource = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTION) ?: return
        val activeSource = style.getSourceAs<GeoJsonSource>(SOURCE_ACTIVE) ?: return
        val jitterSource = style.getSourceAs<GeoJsonSource>(SOURCE_JITTER) ?: return

        // Drawn whether or not spoofing is running: the marker is how the user
        // sees where they just tapped, and hiding it until the start button was
        // pressed meant placing a target produced no feedback at all.
        selectionSource.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(selection.lon, selection.lat)).apply {
                addStringProperty(PROP_LABEL, selectionLabel)
            }
        )

        activeSource.setGeoJson(
            FeatureCollection.fromFeatures(
                if (active == null) emptyList() else listOf(
                    Feature.fromGeometry(Point.fromLngLat(active.lon, active.lat)).apply {
                        addStringProperty(PROP_LABEL, context.getString(R.string.marker_active))
                    }
                )
            )
        )

        // Both branches must be the same type, or no setGeoJson overload matches.
        jitterSource.setGeoJson(
            FeatureCollection.fromFeatures(
                if (active != null && radiusMetres > 0) {
                    listOf(jitterPolygon(active.lat, active.lon, radiusMetres))
                } else {
                    emptyList()
                }
            )
        )
    }

    /** Shows a search result at [place], or clears it when null. */
    fun drawCandidate(style: Style, place: LatLon?) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_CANDIDATE) ?: return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                if (place == null) emptyList() else listOf(
                    Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)).apply {
                        addStringProperty(PROP_LABEL, context.getString(R.string.marker_candidate))
                    }
                )
            )
        )
    }

    /**
     * Redraws the wandering fix.
     *
     * It evaluates the same function the hooks do, against the same clock, so
     * this is the position apps are actually being given rather than a
     * lookalike — that is the point of the walk being a function of time rather
     * than state accumulated inside each hooked process.
     */
    fun drawLive(style: Style, active: LatLon?, radiusMetres: Double, mode: JitterEngine.Mode) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_LIVE) ?: return
        val features = if (active == null) {
            emptyList()
        } else {
            val sample = JitterEngine.sample(radiusMetres, mode, System.currentTimeMillis())
            val (fixLat, fixLng) = JitterEngine.offset(
                active.lat, active.lon, sample.dEastMeters, sample.dNorthMeters
            )
            listOf(Feature.fromGeometry(Point.fromLngLat(fixLng, fixLat)))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // --- icons --------------------------------------------------------------

    private val density get() = context.resources.displayMetrics.density

    /**
     * A hollow pin for the selection: an outline reads as "under consideration"
     * next to the filled dot of something already running, and the teardrop
     * points at a spot while the dot marks one.
     */
    private fun markerPin(fill: Int, stroke: Int): Bitmap {
        val d = density
        val width = (28 * d).toInt()
        val height = (40 * d).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = width / 2f
        val silhouette = Path().apply {
            addCircle(radius, radius, radius, Path.Direction.CW)
            moveTo(radius - radius * 0.62f, radius + radius * 0.66f)
            lineTo(radius, height.toFloat())
            lineTo(radius + radius * 0.62f, radius + radius * 0.66f)
            close()
        }
        // Silhouette first, then the interior punched out of it, so the two
        // shapes meet without a seam down the join.
        paint.color = stroke
        canvas.drawPath(silhouette, paint)
        paint.color = fill
        canvas.drawCircle(radius, radius, radius - 3.5f * d, paint)
        return bitmap
    }

    /** A filled dot with a soft halo for the position being simulated. */
    private fun markerDot(fill: Int): Bitmap {
        val d = density
        val size = (44 * d).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ColorUtils.setAlphaComponent(fill, 55)
        canvas.drawCircle(centre, centre, centre, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(centre, centre, 11f * d, paint)
        paint.color = fill
        canvas.drawCircle(centre, centre, 8f * d, paint)
        return bitmap
    }

    /**
     * The live fix: small, solid, no halo — it has to read as a moving point
     * rather than a second anchor.
     */
    private fun liveDot(fill: Int): Bitmap {
        val d = density
        val size = (16 * d).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(centre, centre, 6f * d, paint)
        paint.color = fill
        canvas.drawCircle(centre, centre, 4f * d, paint)
        return bitmap
    }

    /**
     * The jitter area as a geographic polygon rather than a pixel-radius
     * circle, so it keeps matching the real radius at every zoom without being
     * recomputed on each camera move.
     */
    private fun jitterPolygon(centreLat: Double, centreLon: Double, radiusMetres: Double): Feature {
        val ring = (0..CIRCLE_SEGMENTS).map { i ->
            val angle = 2.0 * Math.PI * i / CIRCLE_SEGMENTS
            val dEast = Math.cos(angle) * radiusMetres
            val dNorth = Math.sin(angle) * radiusMetres
            val dLat = Math.toDegrees(dNorth / EARTH_RADIUS_M)
            val dLon = Math.toDegrees(
                dEast / (EARTH_RADIUS_M * Math.cos(Math.toRadians(centreLat)))
            )
            Point.fromLngLat(centreLon + dLon, centreLat + dLat)
        }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }

    companion object {
        private const val SOURCE_SELECTION = "telequant-selection"
        private const val SOURCE_ACTIVE = "telequant-active"
        private const val SOURCE_JITTER = "telequant-jitter"
        private const val SOURCE_LIVE = "telequant-live"
        private const val SOURCE_CANDIDATE = "telequant-candidate"
        private const val LAYER_CANDIDATE = "telequant-candidate"
        private const val LAYER_SELECTION = "telequant-selection"
        private const val LAYER_ACTIVE = "telequant-active"
        private const val LAYER_LIVE = "telequant-live"
        private const val LAYER_JITTER_FILL = "telequant-jitter-fill"
        private const val LAYER_JITTER_LINE = "telequant-jitter-line"
        private const val IMAGE_SELECTION = "telequant-selection-icon"
        private const val IMAGE_ACTIVE = "telequant-active-icon"
        private const val IMAGE_LIVE = "telequant-live-icon"
        private const val PROP_LABEL = "label"
        private const val CIRCLE_SEGMENTS = 64
        private const val EARTH_RADIUS_M = 6378137.0

        /**
         * MapLibre defaults to "Open Sans Regular, Arial Unicode MS Regular",
         * which OpenFreeMap does not host — the glyph request 404s and every
         * label silently fails to draw. This is the stack the style itself uses.
         */
        private val MAP_FONT = arrayOf("Noto Sans Regular")

        /**
         * The live marker is deliberately not the theme accent: the accent is
         * already the selection ring, and two shades of one colour is the thing
         * the user asked to be able to tell apart at a glance.
         */
        val COLOR_ACTIVE: Int = Color.parseColor("#00A86B")
    }
}
