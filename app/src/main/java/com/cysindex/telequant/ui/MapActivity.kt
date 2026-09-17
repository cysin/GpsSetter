package com.cysindex.telequant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
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
            startRecording()
        }

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

            // Long press rather than tap: a tap cannot be told apart from the
            // start of a pan, so tapping to place meant nudging the target
            // constantly while navigating.
            map.addOnMapLongClickListener { point ->
                moveTarget(point.latitude, point.longitude, recentre = false)
                true
            }
        }
    }

    /**
     * Installs the two layers the target is drawn with.
     *
     * Core style layers rather than the annotation plugin: the newest plugin
     * (3.0.2) is built against MapLibre 11.3.0 and would be force-upgraded two
     * major versions to 13.6.1 here. That compiles but could break at runtime,
     * which is the one thing that cannot be checked from a build machine.
     */
    private fun installTargetLayers(loaded: Style) {
        loaded.addSource(GeoJsonSource(SOURCE_TARGET))
        loaded.addSource(GeoJsonSource(SOURCE_JITTER))

        val accent = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)

        loaded.addLayer(
            FillLayer(LAYER_JITTER_FILL, SOURCE_JITTER).withProperties(
                PropertyFactory.fillColor(accent),
                PropertyFactory.fillOpacity(0.18f)
            )
        )
        loaded.addLayer(
            LineLayer(LAYER_JITTER_LINE, SOURCE_JITTER).withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(1.5f)
            )
        )
        loaded.addLayer(
            SymbolLayer(LAYER_TARGET, SOURCE_TARGET).withProperties(
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, -1.2f)),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textColor(accent),
                PropertyFactory.textAllowOverlap(true)
            )
        )
    }

    /** Redraws the target marker and its jitter radius from [lat]/[lon]. */
    private fun redrawTarget() {
        val loaded = style ?: return
        val targetSource = loaded.getSourceAs<GeoJsonSource>(SOURCE_TARGET) ?: return
        val jitterSource = loaded.getSourceAs<GeoJsonSource>(SOURCE_JITTER) ?: return

        // Drawn whether or not spoofing is running: the marker is how the user
        // sees where they just long-pressed, and hiding it until the start
        // button was pressed meant placing a target produced no feedback at all.
        val marker = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            addStringProperty(PROP_LABEL, "%.6f, %.6f".format(lat, lon))
        }
        targetSource.setGeoJson(marker)

        val radius = PrefManager.jitterRadius?.toDoubleOrNull() ?: 0.0
        // Both branches must be the same type, or no setGeoJson overload matches.
        jitterSource.setGeoJson(
            FeatureCollection.fromFeatures(
                if (radius > 0) listOf(jitterPolygon(lat, lon, radius)) else emptyList()
            )
        )
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

    private fun moveTarget(newLat: Double, newLon: Double, recentre: Boolean) {
        lat = newLat
        lon = newLon
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

        binding.bottomSheetContainer.startSpoofing.setOnClickListener {
            if (!notificationsChannel.hasPermission(this)) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.update(true, lat, lon)
            spoofing = true
            updateStartStopVisibility()
            redrawTarget()
            showStartNotification(
                binding.bottomSheetContainer.firstAddress.text.toString()
                    .ifBlank { "%.6f, %.6f".format(lat, lon) }
            )
            showToast(getString(R.string.location_set))
        }

        binding.bottomSheetContainer.stopButton.setOnClickListener {
            viewModel.update(false, lat, lon)
            spoofing = false
            updateStartStopVisibility()
            redrawTarget()
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
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
            if (id == R.id.record_environment) {
                recordEnvironment()
            } else if (id == R.id.offline_map) {
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

    private fun addFavouriteDialog() {
        alertDialog = MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog_layout, null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                viewModel.storeFavorite(editText.text.toString(), lat, lon)
                viewModel.response.observe(this@MapActivity) {
                    if (it == (-1).toLong()) {
                        showToast(getString(R.string.cant_save))
                    } else {
                        showToast(getString(R.string.save))
                    }
                }
            }
            setView(view)
            show()
        }
    }

    private fun openFavouriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favourites))
        val view = layoutInflater.inflate(R.layout.fav, null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = { favourite ->
            val favLat = favourite.lat
            val favLon = favourite.lng
            if (favLat != null && favLon != null) {
                moveTarget(favLat, favLon, recentre = true)
            }
            if (dialog.isShowing) dialog.dismiss()
        }
        favListAdapter.onItemDelete = { viewModel.deleteFavourite(it) }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()
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
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            showToast(getString(R.string.turn_on_location))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        lm.getCurrentLocation(provider, null, mainExecutor) { location ->
            if (location == null) {
                showToast(getString(R.string.address_not_found))
            } else {
                moveTarget(location.latitude, location.longitude, recentre = true)
            }
        }
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

    private fun recordEnvironment() {
        val missing = missingRecordingPermissions()
        if (missing.isNotEmpty()) {
            requestRecordingPermissions.launch(missing)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recording_title)
            .setMessage(R.string.recording_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = runCatching { EnvironmentRecorder(this@MapActivity).record() }
            progress.dismiss()
            result.onSuccess { showRecordingResult(it) }
                .onFailure { showToast(getString(R.string.recording_failed)) }
        }
    }

    private fun showRecordingResult(result: EnvironmentRecorder.Result) {
        val env = result.environment
        val summary = buildString {
            append(
                getString(
                    R.string.recording_summary,
                    env.cells.size, env.wifis.size, env.beacons.size
                )
            )
            if (result.missing.isNotEmpty()) {
                append("\n\n").append(getString(R.string.recording_missing))
                result.missing.forEach { append("\n  • ").append(it) }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recording_done)
            .setMessage(summary)
            .setPositiveButton(R.string.recording_use) { _, _ ->
                PrefManager.activeEnvironment = env.toJson().toString()
                if (env.lat != 0.0 || env.lng != 0.0) {
                    moveTarget(env.lat, env.lng, recentre = true)
                }
                showToast(getString(R.string.recording_applied))
            }
            .setNegativeButton(R.string.recording_discard, null)
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

        const val SOURCE_TARGET = "telequant-target"
        const val SOURCE_JITTER = "telequant-jitter"
        const val LAYER_TARGET = "telequant-target-label"
        const val LAYER_JITTER_FILL = "telequant-jitter-fill"
        const val LAYER_JITTER_LINE = "telequant-jitter-line"
        const val PROP_LABEL = "label"
        const val CIRCLE_SEGMENTS = 64
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
