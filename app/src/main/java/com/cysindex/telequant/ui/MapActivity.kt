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
import android.location.Location
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import com.cysindex.telequant.record.EnvironmentRecorder
import com.cysindex.telequant.spoof.FakeEnvironment
import com.cysindex.telequant.spoof.JitterEngine
import com.cysindex.telequant.spoof.SyntheticEnvironment
import com.cysindex.telequant.spoof.TestEnvironment
import com.cysindex.telequant.ui.map.MapMarkers
import com.cysindex.telequant.ui.map.OfflineDownloadUi
import com.cysindex.telequant.ui.viewmodel.MainViewModel
import com.cysindex.telequant.utils.NotificationsChannel
import com.cysindex.telequant.utils.PrefManager
import com.cysindex.telequant.utils.ext.isNetworkConnected
import com.cysindex.telequant.utils.ext.radioSummary
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
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog

    private val elevationOverlayProvider by lazy { ElevationOverlayProvider(this) }

    private val markers by lazy {
        MapMarkers(this, MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))
    }

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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val label = pendingFavouriteLabel ?: return@registerForActivityResult
            pendingFavouriteLabel = null
            // Go straight to recording with whatever was granted rather than
            // back through the permission check. Re-checking sent a refusal
            // straight back into launch(), and once Android stops showing the
            // prompt for a permission denied twice, that was a tight loop with
            // no UI in it at all.
            if (granted.values.any { it }) {
                recordNow(label)
            } else {
                showToast(getString(R.string.recording_no_permissions))
                saveFavourite(label, null)
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
                markers.install(loaded)
                redrawTarget()
                startLiveFix()
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

    /** Redraws both markers and the jitter ring. */
    private fun redrawTarget() {
        val loaded = style ?: return
        markers.draw(
            loaded,
            selection = MapMarkers.LatLon(lat, lon),
            selectionLabel = selectionLabel(),
            active = activePosition(),
            radiusMetres = PrefManager.jitterRadius?.toDoubleOrNull() ?: 0.0
        )
    }

    /**
     * Redraws the wandering fix while a simulation runs.
     *
     * It evaluates the same function the hooks do, against the same clock, so
     * this is the position apps are actually being given rather than a
     * lookalike — that is the point of the walk being a function of time rather
     * than state accumulated inside each hooked process.
     */
    private val liveTick = object : Runnable {
        override fun run() {
            drawLiveFix()
            binding.root.postDelayed(this, LIVE_REFRESH_MS)
        }
    }

    private fun startLiveFix() {
        binding.root.removeCallbacks(liveTick)
        if (spoofing) binding.root.post(liveTick)
    }

    private fun stopLiveFix() {
        binding.root.removeCallbacks(liveTick)
        drawLiveFix()
    }

    private fun drawLiveFix() {
        val loaded = style ?: return
        val mode = runCatching {
            JitterEngine.Mode.valueOf(PrefManager.jitterMode.orEmpty())
        }.getOrDefault(JitterEngine.Mode.STATIONARY)
        markers.drawLive(
            loaded,
            active = activePosition(),
            radiusMetres = PrefManager.jitterRadius?.toDoubleOrNull() ?: 0.0,
            mode = mode
        )
    }

    /** The position currently being fed to apps, or null when stopped. */
    private fun activePosition(): MapMarkers.LatLon? =
        if (spoofing) MapMarkers.LatLon(PrefManager.getLat, PrefManager.getLng) else null

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
        // Coordinates first, replaced by the name if one arrives. The old
        // placeholder sat there for the whole connect timeout whenever the
        // geocoder was unreachable, which reads as a hang.
        label.text = "%.6f, %.6f".format(lat, lon)
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

    private fun moveTarget(
        newLat: Double,
        newLon: Double,
        recentre: Boolean,
        origin: SelectionOrigin = SelectionOrigin.MANUAL
    ) {
        lat = newLat
        lon = newLon
        selectionOrigin = origin
        // The environment belonged to the point that was just left. Carrying
        // it along meant loading "Office", tapping a park, and having Start
        // offer to replay the office's towers there — a contradictory
        // environment produced by nothing more than a tap. Loading a favourite
        // reattaches its own after this returns.
        selectedEnvironment = null
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
        startLiveFix()
        viewModel.updateXposedState()
        // Radius or style may have been changed in settings while we were away.
        redrawTarget()
    }

    override fun onPause() {
        // Nothing to animate for a screen nobody is looking at.
        binding.root.removeCallbacks(liveTick)
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
            stopLiveFix()
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
            getString(R.string.mode_full_option, radioSummary(environment)),
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
        startLiveFix()
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
        updateModeLabel()
    }

    /**
     * Says which of the two modes is running. Without it the only visible
     * difference between "position only" and "full environment" was which
     * dialog option had been tapped some time ago.
     */
    private fun updateModeLabel() {
        val label = binding.bottomSheetContainer.modeLabel
        if (!spoofing) {
            label.visibility = View.GONE
            return
        }
        // recordedAt == 0 marks the environment this app synthesised itself.
        val active = FakeEnvironment.parse(PrefManager.activeEnvironment)
        label.text = if (active != null && active.recordedAt != 0L) {
            getString(R.string.mode_running_full, radioSummary(active))
        } else {
            getString(R.string.mode_running_position)
        }
        label.visibility = View.VISIBLE
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
        val searchBox = binding.bottomSheetContainer.search.searchBox
        val clear = binding.bottomSheetContainer.search.searchClear
        clear.setOnClickListener {
            searchBox.text?.clear()
            searchBox.requestFocus()
        }
        // Only there while there is something to clear; a permanent × next to
        // an empty field is a control that does nothing.
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val input = v.text.toString()
            if (input.isNotEmpty()) search(input)
            true
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mapContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            bottom.peekHeight =
                binding.bottomSheetContainer.searchLayout.measuredHeight + bars.bottom

            val searchParams =
                binding.bottomSheetContainer.searchLayout.layoutParams as MarginLayoutParams
            // Insets are delivered again on rotation and whenever the keyboard
            // appears. Adding to the current margin each time walked the search
            // box down the screen, so base it on the value from the layout.
            if (baseSearchBottomMargin < 0) baseSearchBottomMargin = searchParams.bottomMargin
            searchParams.bottomMargin = baseSearchBottomMargin + bars.bottom
            binding.navView.setPadding(0, bars.top, 0, 0)

            // Make room for the keyboard by shrinking the sheet's parent. With
            // decorFitsSystemWindows off the window does not resize for the
            // IME, the manifest asks for adjustNothing so the system does not
            // pan the window either, and BottomSheetBehavior places the sheet
            // by its parent's *height* — so a bottom margin on the parent is
            // what moves it. Padding the parent moved nothing, and a
            // translation on the sheet was reset by the next layout pass.
            // The deprecated systemWindowInsetBottom this used to read does not
            // include the IME on API 30+ at all, which is why the keyboard
            // covered the search box and whatever was typed was typed blind.
            val lift = (ime.bottom - bars.bottom).coerceAtLeast(0)
            (binding.coordinator.layoutParams as MarginLayoutParams).let { lp ->
                if (lp.bottomMargin != lift) {
                    lp.bottomMargin = lift
                    binding.coordinator.layoutParams = lp
                }
            }

            // Consumed, as before: passed through, the parent layouts also make
            // room for the keyboard and the sheet ends up lifted twice.
            WindowInsetsCompat.CONSUMED
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
            // Null and empty mean different things: one is "the geocoder never
            // answered", the other "it answered, and there is no such place".
            // Reporting both as "not found" sent the user hunting for a
            // spelling mistake when the request had not left the device.
            when (val results = Nominatim.search(normalized)) {
                null -> showGeocoderUnreachable()
                emptyList<Nominatim.Place>() -> showToast(getString(R.string.address_not_found))
                else -> if (results.size == 1) {
                    moveTarget(results[0].lat, results[0].lon, recentre = true)
                } else {
                    chooseSearchResult(results)
                }
            }
        }
    }

    /**
     * Names the proxy, because that is the setting that fixes this and there is
     * no way for the user to guess it from "not found".
     */
    private fun showGeocoderUnreachable() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geocoder_unreachable_title)
            .setMessage(
                getString(
                    R.string.geocoder_unreachable_message,
                    PrefManager.proxyHost.orEmpty()
                        .ifBlank { MapEngine.TileDefaults.HOST },
                    PrefManager.proxyPort.orEmpty()
                        .ifBlank { MapEngine.TileDefaults.PORT.toString() },
                    if (PrefManager.proxyGeocoder) {
                        getString(R.string.geocoder_proxy_on)
                    } else {
                        getString(R.string.geocoder_proxy_off)
                    }
                )
            )
            .setPositiveButton(R.string.settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /**
     * Reports what the activation check knows, in the drawer rather than as a
     * dialog on every launch.
     *
     * The check works by the framework loading the module into this app so it
     * can report back. Vector does that regardless of the scope list — verified
     * with TeleQuant absent from its own scope and the status still reading
     * active — but not every framework is guaranteed to, and none of this is
     * needed for spoofing anyway, since hooks are installed with
     * `loadApp(isExcludeSelf = true)` and never reach this app.
     *
     * So a negative answer is worth showing and not worth blocking on. It used
     * to be a modal that release builds would not let you dismiss, which turned
     * a possible false negative into an unusable app.
     */
    private fun isModuleEnable() {
        val status = binding.navView.getHeaderView(0)
            ?.findViewById<TextView>(R.id.header_status) ?: return
        viewModel.isXposed.observe(this) { isXposed ->
            status.text = getString(
                if (isXposed) R.string.module_active else R.string.module_unconfirmed
            )
            status.setOnClickListener {
                if (isXposed) return@setOnClickListener
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.error_xposed_module_missing)
                    .setMessage(R.string.error_xposed_module_missing_desc)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
        recordNow(label)
    }

    /** Records with the permissions currently held; the recorder reports what it lacked. */
    private fun recordNow(label: String) {
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
                showToast(getString(R.string.recording_summary, radioSummary(env)))
            }.onFailure {
                showToast(getString(R.string.recording_failed))
                saveFavourite(label, null)
            }
        }
    }

    private fun saveFavourite(label: String, environment: FakeEnvironment?) {
        lifecycleScope.launch {
            val id = viewModel.storeFavorite(
                address = label,
                lat = lat,
                lon = lon,
                environment = environment?.toJson()?.toString(),
                capturedAt = if (environment != null) System.currentTimeMillis() else 0L
            )
            showToast(getString(if (id == -1L) R.string.cant_save else R.string.save))
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
                        getString(R.string.favourite_loaded_full, radioSummary(it))
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
     * injected into itself, in its own scope or out of it.
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
        showToast(getString(R.string.locating))
        DeviceLocator(this).locate(object : DeviceLocator.Callback {
            override fun onProvidersOff() {
                showToast(getString(R.string.turn_on_location))
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }

            override fun onFix(location: Location) = applyRealFix(location)

            override fun onNothing() = showToast(getString(R.string.address_not_found))
        })
    }

    private fun applyRealFix(location: Location) {
        lastRealFix = location
        moveTarget(
            location.latitude,
            location.longitude,
            recentre = true,
            origin = SelectionOrigin.REAL_FIX
        )
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
                    radioSummary(env), TestEnvironment.OPERATOR, TestEnvironment.SSID_PREFIX
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

    private fun downloadCurrentArea() {
        mapLibre?.let { OfflineDownloadUi(this).offer(it) }
    }

    private companion object {
        const val PERMISSION_ID = 42
        const val DEFAULT_ZOOM = 15.0
        const val ADDRESS_DEBOUNCE_MS = 600L

        /**
         * How often the live fix is redrawn. Fast enough to read as motion,
         * slow enough that it is not redrawing the map every frame.
         */
        const val LIVE_REFRESH_MS = 400L

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
