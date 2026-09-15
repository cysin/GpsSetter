package com.cysindex.telequant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
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
import com.cysindex.telequant.ui.viewmodel.MainViewModel
import com.cysindex.telequant.utils.JoystickService
import com.cysindex.telequant.utils.NotificationsChannel
import com.cysindex.telequant.utils.PrefManager
import com.cysindex.telequant.utils.ext.getAddress
import com.cysindex.telequant.utils.ext.isNetworkConnected
import com.cysindex.telequant.utils.ext.showToast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

class MapActivity : AppCompatActivity() {

    private val TAG = "TeleQuantMap"
    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private lateinit var map: MapView
    private val viewModel by viewModels<MainViewModel> { MainViewModel.Factory }
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mGeoPoint: GeoPoint? = null
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
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

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // setContentView must run before anything touches `binding`; the previous
        // version inflated it inside launchWhenCreated and then immediately called
        // setSupportActionBar(binding.toolbar) on the main thread, racing the coroutine.
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        initializeMap()
        isModuleEnable()
        setBottomSheet()
        setUpNavigationView()
        applyThemeColors()
        setupButton()
        setDrawer()
        if (PrefManager.isJoyStickEnable) {
            startService(Intent(this, JoystickService::class.java))
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupButton() {
        binding.favourite.setOnClickListener { addFavouriteDialog() }
        binding.getlocationContainer.setOnClickListener { getLastLocation() }

        if (viewModel.isStarted) {
            binding.bottomSheetContainer.startSpoofing.visibility = View.GONE
            binding.bottomSheetContainer.stopButton.visibility = View.VISIBLE
        }

        binding.bottomSheetContainer.startSpoofing.setOnClickListener {
            if (!notificationsChannel.hasPermission(this)) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.update(true, lat, lon)
            mGeoPoint?.let { mMarker?.position = it }
            showMarker()
            binding.bottomSheetContainer.startSpoofing.visibility = View.GONE
            binding.bottomSheetContainer.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mGeoPoint?.getAddress(this@MapActivity)?.collect { value ->
                    showStartNotification(value)
                }
            }
            showToast(getString(R.string.location_set))
        }

        binding.bottomSheetContainer.stopButton.setOnClickListener {
            mGeoPoint?.let { viewModel.update(false, it.latitude, it.longitude) }
            hideMarker()
            binding.bottomSheetContainer.stopButton.visibility = View.GONE
            binding.bottomSheetContainer.startSpoofing.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }

    private fun setDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
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
        binding.container.addDrawerListener(mDrawerToggle)
    }

    private fun setBottomSheet() {
        val bottom = BottomSheetBehavior.from(binding.bottomSheetContainer.bottomSheet)
        with(binding.bottomSheetContainer) {
            search.searchBox.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    if (isNetworkConnected()) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            val getInput = v.text.toString()
                            if (getInput.isNotEmpty()) {
                                when (val result = getSearchAddress(getInput)) {
                                    is SearchProgress.Complete -> {
                                        lat = result.lat
                                        lon = result.lon
                                        moveMapToNewLocation(true)
                                    }

                                    is SearchProgress.Fail -> showToast(result.error!!)
                                    SearchProgress.Progress -> Unit
                                }
                            }
                        }
                    } else {
                        showToast(getString(R.string.no_internet))
                    }
                    return@setOnEditorActionListener true
                }
                return@setOnEditorActionListener false
            }
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
            searchParams.bottomMargin = bottomInset + searchParams.bottomMargin
            binding.navView.setPadding(0, topInset, 0, 0)

            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }

        bottom.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /** Replaces MonetCompat; Material 3 dynamic color is applied app-wide in [App]. */
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
            // AGP 9 compiles apps against non-final R fields, so resource ids can no
            // longer appear in `when`/switch branches.
            val id = it.itemId
            if (id == R.id.get_favourite) {
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

    private fun initializeMap() {
        Configuration.getInstance().userAgentValue = packageName
        map = binding.mapContainer
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.setUseDataConnection(true)
        map.maxZoomLevel = 22.0
        map.minZoomLevel = 1.0

        lat = viewModel.getLat
        lon = viewModel.getLng

        val mapController = map.controller
        mGeoPoint = GeoPoint(lat, lon)
        mapController.setZoom(16.0)
        mapController.setCenter(mGeoPoint)

        mMarker = Marker(map).apply {
            position = mGeoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = markerTitle(mGeoPoint)
        }

        map.overlays.add(object : Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                if (e != null && mapView != null) {
                    val geoPoint = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                    onMapClick(GeoPoint(geoPoint.latitude, geoPoint.longitude))
                }
                return true
            }
        })

        if (viewModel.isStarted) {
            map.overlays.add(mMarker)
        }
        map.invalidate()
    }

    private fun markerTitle(point: GeoPoint?): String =
        "Lat: %.6f, Lon: %.6f".format(point?.latitude ?: 0.0, point?.longitude ?: 0.0)

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

    private fun onMapClick(geoPoint: GeoPoint) {
        mGeoPoint = geoPoint
        mMarker?.let { marker ->
            marker.position = geoPoint
            marker.title = markerTitle(geoPoint)
            if (!map.overlays.contains(marker)) {
                map.overlays.add(marker)
            }
            map.controller.animateTo(geoPoint)
            lat = geoPoint.latitude
            lon = geoPoint.longitude
            map.invalidate()
        }
    }

    private fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (!moveNewLocation) return
        mGeoPoint = GeoPoint(lat, lon)
        mGeoPoint?.let { geoPoint ->
            map.controller.animateTo(geoPoint)
            map.controller.setZoom(16.0)
            mMarker?.position = geoPoint
            mMarker?.title = markerTitle(geoPoint)
            if (!map.overlays.contains(mMarker)) {
                map.overlays.add(mMarker)
            }
            map.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        viewModel.updateXposedState()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

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
                val s = editText.text.toString()
                if (!map.overlays.contains(mMarker)) {
                    showToast(getString(R.string.location_not_select))
                } else {
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(this@MapActivity) {
                        if (it == (-1).toLong()) {
                            showToast(getString(R.string.cant_save))
                        } else {
                            showToast(getString(R.string.save))
                        }
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
        favListAdapter.onItemClick = {
            lat = it.lat!!
            lon = it.lng!!
            moveMapToNewLocation(true)
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

    private suspend fun getSearchAddress(address: String): SearchProgress =
        withContext(Dispatchers.IO) {
            val matcher: Matcher = Pattern
                .compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?")
                .matcher(address)

            if (matcher.matches()) {
                delay(300)
                val parts = matcher.group().split(",")
                return@withContext SearchProgress.Complete(
                    parts[0].trim().toDouble(),
                    parts[1].trim().toDouble()
                )
            }

            if (!isNetworkConnected()) {
                return@withContext SearchProgress.Fail(getString(R.string.no_internet))
            }

            try {
                val addressList = Geocoder(this@MapActivity).getFromLocationName(address, 3)
                when {
                    addressList.isNullOrEmpty() ->
                        SearchProgress.Fail(getString(R.string.address_not_found))

                    else -> SearchProgress.Complete(
                        addressList[0].latitude,
                        addressList[0].longitude
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "Geocoding error", e)
                SearchProgress.Fail(getString(R.string.address_not_found))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid address input", e)
                SearchProgress.Fail(getString(R.string.enter_valid_input))
            }
        }

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

    /**
     * Uses the platform LocationManager instead of Play Services so the app works
     * on GMS-free devices (a realistic case for a rooted/Xposed audience).
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
            showToast("Turn on location")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        lm.getCurrentLocation(provider, null, mainExecutor) { location ->
            if (location == null) {
                showToast(getString(R.string.address_not_found))
            } else {
                lat = location.latitude
                lon = location.longitude
                moveMapToNewLocation(true)
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

    private fun showMarker() {
        if (mMarker != null && !map.overlays.contains(mMarker)) {
            map.overlays.add(mMarker)
            map.invalidate()
        }
    }

    private fun hideMarker() {
        if (mMarker != null) {
            map.overlays.remove(mMarker)
            map.invalidate()
        }
    }

    private companion object {
        const val PERMISSION_ID = 42
    }
}

sealed class SearchProgress {
    data object Progress : SearchProgress()
    data class Complete(val lat: Double, val lon: Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
