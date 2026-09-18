package com.cysindex.telequant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cysindex.telequant.BuildConfig
import com.cysindex.telequant.R
import com.cysindex.telequant.adapter.FavListAdapter
import com.cysindex.telequant.databinding.ActivityMapBinding
import com.cysindex.telequant.map.MapEngine
import com.cysindex.telequant.map.Nominatim
import com.cysindex.telequant.map.OfflineRegions
import com.cysindex.telequant.record.EnvironmentRecorder
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.spoof.SyntheticEnvironment
import com.cysindex.telequant.spoof.TestEnvironment
import com.cysindex.telequant.ui.viewmodel.MainViewModel
import com.cysindex.telequant.utils.NotificationsChannel
import com.cysindex.telequant.utils.PrefManager
import com.cysindex.telequant.utils.ext.isNetworkConnected
import com.cysindex.telequant.utils.ext.showToast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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
import java.util.regex.Pattern
import kotlin.properties.Delegates

class MapActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private lateinit var mapView: MapView
    private var mapLibre: MapLibreMap? = null
    private var style: Style? = null

    private val viewModel by viewModels<MainViewModel> { MainViewModel.Factory }
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
    private var spoofing = false

    /**
     * Where the selected point came from. It decides what can honestly be saved
     * with it: a point the device actually reported sits in the middle of the
     * radio environment a recording would capture, and a point chosen on the map
     * may be anywhere at all.
     */
    private enum class SelectionOrigin { MANUAL, REAL_FIX }

    private var selectionOrigin = SelectionOrigin.MANUAL

    /**
     * The last position the device itself reported, kept so the favourite dialog
     * can say how far the chosen point is from it. Never used to move anything.
     */
    private var lastRealFix: Location? = null

    /**
     * The radio environment attached to the current selection — loaded with a
     * favourite, or captured by a recording. Null means there is nothing to
     * replay and Start simulates the position alone.
     */
    private var selectedEnvironment: FakeEnvironment? = null

    private var baseSearchBottomMargin = -1
    private var addressJob: Job? = null
    private var xposedDialog: AlertDialog? = null
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog

    private val elevationOverlayProvider by lazy { ElevationOverlayProvider(this) }

    /**
     * POST_NOTIFICATIONS is a runtime permission since Android 13; without it the
     * "location set" notification is dropped silently.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Recording asks for several permissions at once. A refusal is not fatal:
     * the recorder degrades that one signal and reports it, so the result
     * dialog can say exactly what is missing rather than silently storing a
     * half-empty environment.
     */
    private val requestRecordingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Resume whichever flow asked. Without this a save-with-recording
            // came back from the permission prompt as a bare recording, and the
            // name the user had just typed was gone.
            pendingFavouriteLabel?.let {
                pendingFavouriteLabel = null
                recordThenSave(it)
            }
        }

    /** Set while a favourite save is waiting on the recording permissions. */
    private var pendingFavouriteLabel: String? = null

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brings the native renderer up; must precede inflating the MapView.
        MapEngine.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        lat = viewModel.getLat
        lon = viewModel.getLng
        spoofing = viewModel.isStarted
        // Survives a restart while a simulation is running, so Start still
        // offers to replay whatever is active. A recordedAt of 0 marks the
        // environment this app synthesised for position-only mode, which is
        // rebuilt from the coordinate rather than remembered.
        selectedEnvironment = FakeEnvironment.parse(PrefManager.activeEnvironment)
            ?.takeIf { it.recordedAt != 0L }

        initializeMap(savedInstanceState)
        isModuleEnable()
        setBottomSheet()
        setUpNavigationView()
        applyThemeColors()
        setupButton()
        setDrawer()
    }

    // --- map ----------------------------------------------------------------

    private fun initializeMap(savedInstanceState: Bundle?) {
        mapView = binding.mapContainer
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibre = map
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(DEFAULT_ZOOM)
                .build()

            map.setStyle(MapEngine.styleUrl()) { loaded ->
                style = loaded
                // Sources and layers can only be added once the style is loaded.
                installTargetLayers(loaded)
                redrawTarget()
                updateAddressLabel()
            }

            // A single tap places the point. The worry that this would fight
            // with panning does not hold: MapLibre reports a *confirmed* single
            // tap, which neither a pan (a scroll gesture) nor a double-tap zoom
            // (the second tap cancels the confirmation) produces.
            map.addOnMapClickListener { point ->
                moveTarget(point.latitude, point.longitude, recentre = false)
                true
            }
        }
    }

    /**
     * Installs the layers the two markers are drawn with.
     *
     * Core style layers rather than the annotation plugin: the newest plugin
     * (3.0.2) is built against MapLibre 11.3.0 and would be force-upgraded two
     * major versions to 13.6.1 here. That compiles but could break at runtime,
     * which is the one thing that cannot be checked from a build machine.
     *
     * Two markers, because they answer different questions. The selection is
     * where the next Start would put you; the active marker is where apps are
     * being told you are *right now*. They coincide most of the time, and the
     * moment they stop coinciding — picking a new place without restarting, or
     * panning away from a running simulation — is exactly when one marker
     * cannot say which is which.
     */
    private fun installTargetLayers(loaded: Style) {
        loaded.addSource(GeoJsonSource(SOURCE_SELECTION))
        loaded.addSource(GeoJsonSource(SOURCE_ACTIVE))
        loaded.addSource(GeoJsonSource(SOURCE_JITTER))

        val accent = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)

        // The jitter ring belongs to the active marker: it is the area apps are
        // actually being given, not a property of a place under consideration.
        loaded.addLayer(
            FillLayer(LAYER_JITTER_FILL, SOURCE_JITTER).withProperties(
                PropertyFactory.fillColor(COLOR_ACTIVE),
                PropertyFactory.fillOpacity(0.16f)
            )
        )
        loaded.addLayer(
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
        loaded.addImage(IMAGE_SELECTION, markerPin(Color.WHITE, accent))
        loaded.addImage(IMAGE_ACTIVE, markerDot(COLOR_ACTIVE))

        // Different shape *and* different colour. One of the two alone survives
        // neither a colour-blind user nor a greyscale screenshot.
        loaded.addLayer(
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
        loaded.addLayer(
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
    }

    /**
     * A hollow pin for the selection: an outline reads as "under consideration"
     * next to the filled dot of something already running, and the teardrop
     * points at a spot while the dot marks one.
     */
    private fun markerPin(fill: Int, stroke: Int): Bitmap {
        val d = resources.displayMetrics.density
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
        val d = resources.displayMetrics.density
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

    /** Redraws both markers and the jitter ring. */
    private fun redrawTarget() {
        val loaded = style ?: return
        val selectionSource = loaded.getSourceAs<GeoJsonSource>(SOURCE_SELECTION) ?: return
        val activeSource = loaded.getSourceAs<GeoJsonSource>(SOURCE_ACTIVE) ?: return
        val jitterSource = loaded.getSourceAs<GeoJsonSource>(SOURCE_JITTER) ?: return

        // Drawn whether or not spoofing is running: the marker is how the user
        // sees where they just long-pressed, and hiding it until the start
        // button was pressed meant placing a target produced no feedback at all.
        selectionSource.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty(PROP_LABEL, selectionLabel())
            }
        )

        // The active marker is the one the user asked to appear only while
        // something is actually being simulated.
        val active = activePosition()
        activeSource.setGeoJson(
            FeatureCollection.fromFeatures(
                if (active == null) emptyList() else listOf(
                    Feature.fromGeometry(Point.fromLngLat(active.second, active.first)).apply {
                        addStringProperty(PROP_LABEL, getString(R.string.marker_active))
                    }
                )
            )
        )

        val radius = PrefManager.jitterRadius?.toDoubleOrNull() ?: 0.0
        // Both branches must be the same type, or no setGeoJson overload matches.
        jitterSource.setGeoJson(
            FeatureCollection.fromFeatures(
                if (active != null && radius > 0) {
                    listOf(jitterPolygon(active.first, active.second, radius))
                } else {
                    emptyList()
                }
            )
        )
    }

    /** The position currently being fed to apps, or null when stopped. */
    private fun activePosition(): Pair<Double, Double>? =
        if (spoofing) PrefManager.getLat to PrefManager.getLng else null

    private fun selectionLabel(): String {
        val origin = when (selectionOrigin) {
            SelectionOrigin.REAL_FIX -> getString(R.string.marker_selection_real)
            SelectionOrigin.MANUAL -> getString(R.string.marker_selection_manual)
        }
        return "$origin\n%.6f, %.6f".format(lat, lon)
    }

    /**
     * Reverse-geocodes the current target into the bottom sheet.
     *
     * Debounced through a single job: long-pressing repeatedly would otherwise
     * fire one Nominatim request per press, and their usage policy asks for
     * about one request per second.
     */
    private fun updateAddressLabel() {
        val label = binding.bottomSheetContainer.firstAddress
        addressJob?.cancel()
        label.text = getString(R.string.address_looking_up)
        addressJob = lifecycleScope.launch {
            delay(ADDRESS_DEBOUNCE_MS)
            val requestedLat = lat
            val requestedLon = lon
            val name = Nominatim.reverse(requestedLat, requestedLon)
            // The target may have moved again while the request was in flight.
            if (requestedLat == lat && requestedLon == lon) {
                label.text = name ?: "%.6f, %.6f".format(lat, lon)
            }
        }
    }

    /**
     * The jitter area as a geographic polygon rather than a pixel-radius
     * circle, so it keeps matching the real radius at every zoom without being
     * recomputed on each camera move.
     */
    private fun jitterPolygon(
        centreLat: Double,
        centreLon: Double,
        radiusMetres: Double
    ): Feature {
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

    private fun moveTarget(
        newLat: Double,
        newLon: Double,
        recentre: Boolean,
        origin: SelectionOrigin = SelectionOrigin.MANUAL
    ) {
        lat = newLat
        lon = newLon
        selectionOrigin = origin
        if (recentre) {
            mapLibre?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), DEFAULT_ZOOM)
            )
        }
        redrawTarget()
        updateAddressLabel()
    }

    // --- lifecycle ----------------------------------------------------------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        viewModel.updateXposedState()
        // Radius or style may have been changed in settings while we were away.
        redrawTarget()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    // --- controls -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun setupButton() {
        binding.favourite.setOnClickListener { addFavouriteDialog() }
        binding.getlocationContainer.setOnClickListener { getLastLocation() }

        updateStartStopVisibility()

        binding.bottomSheetContainer.startSpoofing.setOnClickListener { askModeAndStart() }

        binding.bottomSheetContainer.stopButton.setOnClickListener {
            viewModel.update(false, lat, lon)
            spoofing = false
            updateStartStopVisibility()
            redrawTarget()
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }

    /**
     * Offers the choice between replaying a recorded environment and simulating
     * the position alone — but only when there is a recording to choose. With
     * nothing attached to the selection the question has one answer, and asking
     * it anyway is a dialog that teaches the user to dismiss dialogs.
     */
    private fun askModeAndStart() {
        val environment = selectedEnvironment
        if (environment == null) {
            start(positionOnlyEnvironment())
            return
        }
        val options = arrayOf(
            getString(
                R.string.mode_full_option,
                environment.cells.size, environment.wifis.size, environment.beacons.size
            ),
            getString(R.string.mode_position_option)
        )
        var chosen = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mode_title)
            .setSingleChoiceItems(options, 0) { _, which -> chosen = which }
            .setPositiveButton(R.string.start) { _, _ ->
                start(
                    if (chosen == 0) environment.copy(lat = lat, lng = lon)
                    else positionOnlyEnvironment()
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Pushes a newly chosen environment out while a simulation is already
     * running. Without this, loading a recording mid-run changes what Start
     * *would* do and nothing about what apps are currently being told.
     */
    private fun reapplyIfRunning() {
        if (!spoofing) return
        val environment = selectedEnvironment?.copy(lat = lat, lng = lon)
            ?: positionOnlyEnvironment()
        PrefManager.activeEnvironment = environment.toJson().toString()
        viewModel.update(true, lat, lon)
    }

    private fun start(environment: FakeEnvironment) {
        if (!notificationsChannel.hasPermission(this)) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PrefManager.activeEnvironment = environment.toJson().toString()
        viewModel.update(true, lat, lon)
        spoofing = true
        updateStartStopVisibility()
        redrawTarget()
        showStartNotification(selectionName())
        showToast(getString(R.string.location_set))
    }

    /**
     * The "position only" environment: the subscriber's real operator with
     * fabricated tower and access-point identifiers.
     *
     * Leaving the radio untouched would let a tower-database lookup report the
     * real city, and blanking it produces a combination the platform never
     * emits. See [SyntheticEnvironment].
     */
    private fun positionOnlyEnvironment(): FakeEnvironment {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return SyntheticEnvironment.build(
            lat = lat,
            lng = lon,
            operatorNumeric = runCatching { tm.networkOperator }.getOrNull()?.takeIf { it.length >= 5 },
            operatorName = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            countryIso = runCatching { tm.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() },
            networkType = 13 // LTE, matching the cells this builds.
        )
    }

    private fun updateStartStopVisibility() {
        binding.bottomSheetContainer.startSpoofing.visibility =
            if (spoofing) View.GONE else View.VISIBLE
        binding.bottomSheetContainer.stopButton.visibility =
            if (spoofing) View.VISIBLE else View.GONE
    }

    private fun setDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val drawerToggle = object : ActionBarDrawerToggle(
            this, binding.container, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.addDrawerListener(drawerToggle)
    }

    private fun setBottomSheet() {
        val bottom = BottomSheetBehavior.from(binding.bottomSheetContainer.bottomSheet)
        binding.bottomSheetContainer.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val input = v.text.toString()
            if (input.isNotEmpty()) search(input)
            true
        }

        binding.mapContainer.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset: Int = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottomInset: Int = insets.systemWindowInsetBottom
            bottom.peekHeight =
                binding.bottomSheetContainer.searchLayout.measuredHeight + bottomInset

            val searchParams =
                binding.bottomSheetContainer.searchLayout.layoutParams as MarginLayoutParams
            // Insets are delivered again on rotation and whenever the keyboard
            // appears. Adding to the current margin each time walked the search
            // box down the screen, so base it on the value from the layout.
            if (baseSearchBottomMargin < 0) baseSearchBottomMargin = searchParams.bottomMargin
            searchParams.bottomMargin = baseSearchBottomMargin + bottomInset
            binding.navView.setPadding(0, topInset, 0, 0)

            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }

        bottom.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /**
     * Coordinates are parsed locally; anything else goes to Nominatim. The
     * platform Geocoder used before is backed by Play Services and returns
     * nothing on devices without them, so place search simply never worked
     * there.
     */
    private fun search(input: String) {
        val normalized = normalizeInput(input)
        val matcher = COORDINATE.matcher(normalized)
        if (matcher.matches()) {
            // Capture groups rather than splitting the whole match, so the
            // separator can be a comma or plain whitespace.
            val parsedLat = matcher.group(1)?.toDoubleOrNull()
            val parsedLon = matcher.group(2)?.toDoubleOrNull()
            if (parsedLat != null && parsedLon != null &&
                parsedLat in -90.0..90.0 && parsedLon in -180.0..180.0
            ) {
                moveTarget(parsedLat, parsedLon, recentre = true)
                return
            }
            // Numeric but out of range — say so rather than sending digits to a
            // place-name lookup that will unhelpfully report "not found".
            showToast(getString(R.string.enter_valid_input))
            return
        }

        if (!isNetworkConnected()) {
            showToast(getString(R.string.no_internet))
            return
        }

        lifecycleScope.launch {
            val results = Nominatim.search(normalized)
            when {
                results.isEmpty() -> showToast(getString(R.string.address_not_found))
                results.size == 1 -> results[0].let {
                    moveTarget(it.lat, it.lon, recentre = true)
                }
                else -> chooseSearchResult(results)
            }
        }
    }

    private fun chooseSearchResult(results: List<Nominatim.Place>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_results)
            .setItems(results.map { it.displayName }.toTypedArray()) { _, index ->
                results[index].let { moveTarget(it.lat, it.lon, recentre = true) }
            }
            .show()
    }

    /** Replaces MonetCompat; Material 3 dynamic color is applied app-wide in App. */
    private fun applyThemeColors() {
        val surfaceVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceVariant
        )
        val surface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurface
        )
        binding.bottomSheetContainer.search.searchBox.backgroundTintList =
            ColorStateList.valueOf(surfaceVariant)
        (binding.bottomSheetContainer.root.background as? GradientDrawable)
            ?.setColor(ColorUtils.setAlphaComponent(headerBackground, 235))
        binding.getlocationContainer.backgroundTintList = ColorStateList.valueOf(surface)
        binding.favourite.backgroundTintList = ColorStateList.valueOf(surface)
    }

    private fun setUpNavigationView() {
        binding.navView.setNavigationItemSelectedListener {
            // AGP 9 compiles apps against non-final R fields, so resource ids can
            // no longer appear in `when` branches.
            val id = it.itemId
            if (id == R.id.offline_map) {
                downloadCurrentArea()
            } else if (id == R.id.get_favourite) {
                openFavouriteListDialog()
            } else if (id == R.id.settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else if (id == R.id.about) {
                aboutDialog()
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun isModuleEnable() {
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    setCancelable(BuildConfig.DEBUG)
                    show()
                }
            }
        }
    }

    // --- dialogs ------------------------------------------------------------

    private fun aboutDialog() {
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about, null).apply {
            findViewById<TextView>(R.id.design_about_title).text = getString(R.string.app_name)
            findViewById<TextView>(R.id.design_about_version).text = BuildConfig.VERSION_NAME
            findViewById<TextView>(R.id.design_about_info).text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    /**
     * Saves the selected point, optionally with the radio environment recorded
     * here and now.
     *
     * The recording is offered whatever the point's origin, and the distance
     * from the device's actual position is shown instead of being used to veto
     * it. Capturing the surroundings of one place to replay at another is a
     * legitimate thing to want — binding an office's access points to a point
     * you dropped on its roof, say — and a rule strict enough to block the
     * mistake also blocks that.
     */
    private fun addFavouriteDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_favourite, null)
        val name = view.findViewById<EditText>(R.id.favourite_name)
        val detail = view.findViewById<TextView>(R.id.favourite_detail)
        val record = view.findViewById<CheckBox>(R.id.favourite_record)

        name.setText(selectionName())
        detail.text = selectionDetail()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_fav_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_button_add) { _, _ ->
                val label = name.text.toString().ifBlank { "%.6f, %.6f".format(lat, lon) }
                if (record.isChecked) recordThenSave(label) else saveFavourite(label, null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A name for the selected point: its address once the lookup has landed,
     * its coordinates until then. The placeholder used to end up prefilled as
     * the favourite's name whenever the dialog was opened before the
     * reverse-geocode came back.
     */
    private fun selectionName(): String {
        val address = binding.bottomSheetContainer.firstAddress.text.toString()
        return address
            .takeIf { it.isNotBlank() && it != getString(R.string.address_looking_up) }
            ?.take(80)
            ?: "%.6f, %.6f".format(lat, lon)
    }

    /** Coordinates, where the point came from, and how far that is from here. */
    private fun selectionDetail(): String = buildString {
        append("%.6f, %.6f".format(lat, lon))
        append('\n')
        append(
            when (selectionOrigin) {
                SelectionOrigin.REAL_FIX -> getString(
                    R.string.selection_from_fix,
                    lastRealFix?.accuracy?.toInt() ?: 0
                )

                SelectionOrigin.MANUAL -> getString(R.string.selection_from_map)
            }
        )
        append('\n')
        val fix = lastRealFix
        append(
            when {
                fix == null -> getString(R.string.selection_distance_unknown)
                else -> {
                    val here = Location(fix.provider).apply {
                        latitude = lat
                        longitude = lon
                    }
                    getString(R.string.selection_distance, formatDistance(fix.distanceTo(here)))
                }
            }
        )
    }

    private fun formatDistance(metres: Float): String =
        if (metres < 1000) "%.0f m".format(metres) else "%.2f km".format(metres / 1000)

    private fun recordThenSave(label: String) {
        val missing = missingRecordingPermissions()
        if (missing.isNotEmpty()) {
            pendingFavouriteLabel = label
            requestRecordingPermissions.launch(missing)
            return
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recording_title)
            .setMessage(R.string.recording_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = runCatching { EnvironmentRecorder(this@MapActivity).record(lat, lon) }
            progress.dismiss()
            result.onSuccess {
                // Bound to the chosen point, not to wherever the recorder got
                // its own fix: the whole purpose is to replay these surroundings
                // at the place the user picked.
                val env = it.environment.copy(lat = lat, lng = lon)
                saveFavourite(label, env)
                selectedEnvironment = env
                showToast(
                    getString(
                        R.string.recording_summary,
                        env.cells.size, env.wifis.size, env.beacons.size
                    )
                )
            }.onFailure {
                showToast(getString(R.string.recording_failed))
                saveFavourite(label, null)
            }
        }
    }

    private fun saveFavourite(label: String, environment: FakeEnvironment?) {
        viewModel.storeFavorite(
            address = label,
            lat = lat,
            lon = lon,
            environment = environment?.toJson()?.toString(),
            capturedAt = if (environment != null) System.currentTimeMillis() else 0L
        )
        viewModel.response.observe(this@MapActivity) {
            if (it == (-1).toLong()) {
                showToast(getString(R.string.cant_save))
            } else {
                showToast(getString(R.string.save))
            }
        }
    }

    private fun openFavouriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favourites))
        val view = layoutInflater.inflate(R.layout.fav, null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        val empty = view.findViewById<TextView>(R.id.favorites_empty)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        // Tells "nothing saved yet" apart from "failed to load"; without it an
        // empty list is just blank space.
        favListAdapter.onListChanged = { count ->
            empty.visibility = if (count == 0) View.VISIBLE else View.GONE
            rcv.visibility = if (count == 0) View.GONE else View.VISIBLE
        }
        favListAdapter.onItemClick = { favourite ->
            val favLat = favourite.lat
            val favLon = favourite.lng
            if (favLat != null && favLon != null) {
                moveTarget(favLat, favLon, recentre = true)
                // The saved environment travels with the place, so Start can
                // offer to replay it. Without a recording the selection carries
                // nothing and Start goes straight to position-only.
                selectedEnvironment = FakeEnvironment.parse(favourite.environment)
                showToast(
                    selectedEnvironment?.let {
                        getString(
                            R.string.favourite_loaded_full,
                            it.cells.size, it.wifis.size, it.beacons.size
                        )
                    } ?: getString(R.string.favourite_loaded_position)
                )
            }
            if (dialog.isShowing) dialog.dismiss()
        }
        favListAdapter.onItemDelete = { viewModel.deleteFavourite(it) }
        alertDialog.setView(view)
        // A verification tool rather than an everyday one, so it lives here
        // instead of the main menu — but it stays reachable in release builds,
        // which is what actually gets installed, and it saves the profile as a
        // place so it replays through the same path as everything else.
        alertDialog.setNeutralButton(R.string.test_env, null)
        dialog = alertDialog.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            loadTestEnvironment()
        }
    }

    private fun getAllUpdatedFavList() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect { favListAdapter.submitList(it) }
            }
        }
    }

    // --- notifications ------------------------------------------------------

    private fun showStartNotification(address: String) {
        notificationsChannel.showNotification(this) {
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    private fun cancelNotification() {
        notificationsChannel.cancelAllNotifications(this)
    }

    // --- device location ----------------------------------------------------

    /**
     * Moves the selection to where the device actually is.
     *
     * This reads the real position even while a simulation is running, because
     * the hook entry calls `loadApp(isExcludeSelf = true)` — the module is never
     * injected into itself. The one way to break that is to add this app to its
     * own scope in the Xposed manager, which [warnIfSelfSpoofed] checks for
     * rather than letting the button quietly return the simulated point.
     *
     * Uses the platform LocationManager instead of Play Services, so this works
     * on GMS-free devices — a realistic case for a rooted audience.
     */
    @SuppressLint("MissingPermission")
    private fun getLastLocation() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Fused and network first, GPS last. Asking GPS for a fresh fix indoors
        // returns nothing for as long as you are willing to wait — the button
        // simply appeared dead — while the other two answer in a second from
        // the cell and Wi-Fi environment.
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            showToast(getString(R.string.turn_on_location))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Show the most recent cached fix straight away so the button does
        // something visible, then replace it if a fresh one arrives.
        val cached = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null) applyRealFix(cached) else showToast(getString(R.string.locating))

        val signal = CancellationSignal()
        var delivered = false
        lm.getCurrentLocation(providers.first(), signal, mainExecutor) { location ->
            delivered = true
            if (location != null) {
                applyRealFix(location)
            } else if (cached == null) {
                showToast(getString(R.string.address_not_found))
            }
        }
        // getCurrentLocation has no timeout of its own, and an outstanding
        // request holds the radio awake.
        binding.root.postDelayed({
            if (!delivered) {
                signal.cancel()
                if (cached == null) showToast(getString(R.string.address_not_found))
            }
        }, FIX_TIMEOUT_MS)
    }

    private fun applyRealFix(location: Location) {
        lastRealFix = location
        warnIfSelfSpoofed(location)
        moveTarget(
            location.latitude,
            location.longitude,
            recentre = true,
            origin = SelectionOrigin.REAL_FIX
        )
    }

    /**
     * A "real" fix landing on the simulated point is the signature of this app
     * having been added to its own Xposed scope. Saying so beats letting the
     * user record an environment they believe is their surroundings.
     */
    private fun warnIfSelfSpoofed(fix: Location) {
        if (!spoofing) return
        val simulated = Location(fix.provider).apply {
            latitude = PrefManager.getLat
            longitude = PrefManager.getLng
        }
        if (fix.distanceTo(simulated) > SELF_SPOOF_TOLERANCE_M) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.self_spoof_title)
            .setMessage(R.string.self_spoof_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun checkPermissions(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_ID &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            getLastLocation()
        }
    }

    // --- recording ----------------------------------------------------------

    /** Permissions the recorder wants, filtered to those not already held. */
    private fun missingRecordingPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.filter {
        ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    /**
     * Loads an obviously-synthetic environment, for checking that the cell,
     * Wi-Fi and Bluetooth hooks actually fire.
     *
     * A recording made on this phone cannot answer that question: it contains
     * this phone's real towers, so a hooked read and an unhooked one look
     * identical. These values belong to no real network, so seeing them proves
     * the read went through the hooks.
     */
    private fun loadTestEnvironment() {
        val env = TestEnvironment.build(lat, lon)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.test_env)
            .setMessage(
                getString(
                    R.string.test_env_message,
                    env.cells.size, env.wifis.size, env.beacons.size,
                    TestEnvironment.OPERATOR, TestEnvironment.SSID_PREFIX
                )
            )
            .setPositiveButton(R.string.test_env_apply) { _, _ ->
                selectedEnvironment = env
                saveFavourite(getString(R.string.test_env_place_name), env)
                redrawTarget()
                reapplyIfRunning()
                showToast(getString(R.string.test_env_applied))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- offline ------------------------------------------------------------

    /**
     * Downloads the current viewport for offline use through MapLibre's own
     * offline store.
     *
     * This is legitimate only because the tiles come from OpenFreeMap, which
     * places no limits on requests. The OSM Foundation's policy bans
     * pre-fetching from tile.openstreetmap.org outright, so the previous raster
     * implementation would have got the client blocked.
     */
    private fun downloadCurrentArea() {
        val map = mapLibre ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        val minZoom = map.cameraPosition.zoom.coerceAtLeast(1.0)
        val maxZoom = (minZoom + OFFLINE_EXTRA_ZOOM).coerceAtMost(16.0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_download)
            .setMessage(getString(R.string.offline_confirm, minZoom.toInt(), maxZoom.toInt()))
            .setPositiveButton(R.string.offline_start) { _, _ ->
                runOfflineDownload(bounds, minZoom, maxZoom)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runOfflineDownload(
        bounds: org.maplibre.android.geometry.LatLngBounds,
        minZoom: Double,
        maxZoom: Double
    ) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_downloading)
            .setMessage(getString(R.string.offline_progress_pct, 0))
            .setCancelable(false)
            .show()

        OfflineRegions(this).download(
            name = "%.4f,%.4f".format(bounds.center.latitude, bounds.center.longitude),
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            pixelRatio = resources.displayMetrics.density,
            onProgress = {
                progress.setMessage(
                    getString(R.string.offline_progress_pct, (it.fraction * 100).toInt())
                )
            },
            onComplete = {
                progress.dismiss()
                showToast(getString(R.string.offline_done))
            },
            onError = { reason ->
                progress.dismiss()
                showToast(getString(R.string.offline_failed, reason))
            }
        )
    }

    private companion object {
        const val PERMISSION_ID = 42
        const val DEFAULT_ZOOM = 15.0
        const val ADDRESS_DEBOUNCE_MS = 600L

        /** Zoom levels beyond the current one to also fetch when going offline. */
        const val OFFLINE_EXTRA_ZOOM = 3.0

        const val SOURCE_SELECTION = "telequant-selection"
        const val SOURCE_ACTIVE = "telequant-active"
        const val SOURCE_JITTER = "telequant-jitter"
        const val LAYER_SELECTION = "telequant-selection"
        const val LAYER_ACTIVE = "telequant-active"
        const val IMAGE_SELECTION = "telequant-selection-icon"
        const val IMAGE_ACTIVE = "telequant-active-icon"

        /**
         * MapLibre defaults to "Open Sans Regular, Arial Unicode MS Regular",
         * which OpenFreeMap does not host — the glyph request 404s and every
         * label silently fails to draw. This is the stack the style itself uses.
         */
        val MAP_FONT = arrayOf("Noto Sans Regular")
        const val LAYER_JITTER_FILL = "telequant-jitter-fill"
        const val LAYER_JITTER_LINE = "telequant-jitter-line"

        /**
         * The live marker is deliberately not the theme accent: the accent is
         * already the selection ring, and two shades of one colour is the thing
         * the user asked to be able to tell apart at a glance.
         */
        val COLOR_ACTIVE = Color.parseColor("#00A86B")
        const val PROP_LABEL = "label"
        const val CIRCLE_SEGMENTS = 64

        /**
         * How close a "real" fix has to land to the simulated point before it
         * is treated as this app having been hooked by itself. Wide enough to
         * cover the jitter radius and ordinary GNSS error.
         */
        const val SELF_SPOOF_TOLERANCE_M = 60f

        /** How long to wait for a fresh fix before giving up on the radio. */
        const val FIX_TIMEOUT_MS = 15_000L
        const val EARTH_RADIUS_M = 6378137.0

        /**
         * Separator is a comma or just whitespace, because a coordinate typed on
         * a Chinese IME arrives with a full-width comma and the pair is often
         * pasted space-separated.
         */
        val COORDINATE: Pattern =
            Pattern.compile("([-+]?\\d{1,3}(?:[.]\\d+)?)\\s*[,\\s]\\s*([-+]?\\d{1,3}(?:[.]\\d+)?)")

        /**
         * Folds the full-width forms a Chinese keyboard produces onto ASCII.
         *
         * Without this, typing coordinates on a Chinese IME yields "，" instead
         * of ",", the pattern does not match, and the input silently falls
         * through to a place-name lookup that reports "address not found" — the
         * failure looks like a network problem rather than a parsing one.
         */
        fun normalizeInput(raw: String): String = buildString {
            raw.trim().forEach { c ->
                append(
                    when (c) {
                        '，', '、' -> ','
                        '．', '。' -> '.'
                        '　' -> ' '
                        '－', '−' -> '-'
                        '＋' -> '+'
                        // Full-width digits 0-9
                        in '０'..'９' -> '0' + (c - '０')
                        else -> c
                    }
                )
            }
        }
    }
}
