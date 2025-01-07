package com.cysindex.telequant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.*
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
import com.cysindex.telequant.utils.ext.*
import com.google.android.gms.location.*

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kieronquinn.monetcompat.app.MonetCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates
import org.osmdroid.views.MapView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import android.view.MotionEvent


@AndroidEntryPoint
class MapActivity :  MonetCompatActivity() {

    private val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    private lateinit var map: MapView
    private val viewModel by viewModels<MainViewModel>()
    private val update by lazy { viewModel.getAvailableUpdate() }
    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mGeoPoint: GeoPoint? = null
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
    private var xposedDialog: AlertDialog? = null
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42



    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }
    override val applyBackgroundColorToWindow = true


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            monet.awaitMonetReady()
            setContentView(binding.root)
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        isModuleEnable()
        updateChecker()
        setBottomSheet()
        setUpNavigationView()
        setupMonet()
        setupButton()
        setDrawer()
        if (PrefManager.isJoyStickEnable){
            startService(Intent(this, JoystickService::class.java))
        }

    }


    @SuppressLint("MissingPermission")
    private fun setupButton(){
        binding.favourite.setOnClickListener {
            addFavouriteDialog()
        }
        binding.getlocationContainer.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.bottomSheetContainer.startSpoofing.visibility = View.GONE
            binding.bottomSheetContainer.stopButton.visibility = View.VISIBLE
        }

        binding.bottomSheetContainer.startSpoofing.setOnClickListener {
            viewModel.update(true, lat, lon)
            mGeoPoint?.let {
                mMarker?.position = it
            }
            showMarker()
            binding.bottomSheetContainer.startSpoofing.visibility = View.GONE
            binding.bottomSheetContainer.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mGeoPoint?.let {
                    it.getAddress(this@MapActivity)?.let { address ->
                        address.collect{ value ->
                            showStartNotification(value)
                        }
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.bottomSheetContainer.stopButton.setOnClickListener {
            mGeoPoint?.let {
                viewModel.update(false, it.latitude, it.longitude)
            }
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
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
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
        binding.container.setDrawerListener(mDrawerToggle)

    }

    private fun setBottomSheet(){
        //val progress = binding.bottomSheetContainer.search.searchProgress

        val bottom = BottomSheetBehavior.from(binding.bottomSheetContainer.bottomSheet)
        with(binding.bottomSheetContainer){

            search.searchBox.setOnEditorActionListener { v, actionId, _ ->

                if (actionId == EditorInfo.IME_ACTION_SEARCH) {

                    if (isNetworkConnected()) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            val  getInput = v.text.toString()
                            if (getInput.isNotEmpty()){
                                getSearchAddress(getInput).let {
                                    it.collect { result ->
                                        when(result) {
                                            is SearchProgress.Progress -> {
                                               // progress.visibility = View.VISIBLE
                                            }
                                            is SearchProgress.Complete -> {
                                                lat = result.lat
                                                lon = result.lon
                                                moveMapToNewLocation(true)
                                            }

                                            is SearchProgress.Fail -> {
                                                showToast(result.error!!)
                                            }
                                        }
                                    }
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

            val topInset: Int = insets.systemWindowInsetTop
            val bottomInset: Int = insets.systemWindowInsetBottom
            bottom.peekHeight = binding.bottomSheetContainer.searchLayout.measuredHeight + bottomInset

            val searchParams = binding.bottomSheetContainer.searchLayout.layoutParams as MarginLayoutParams
            searchParams.bottomMargin  = bottomInset + searchParams.bottomMargin
            binding.navView.setPadding(0,topInset,0,0)

            insets.consumeSystemWindowInsets()
        }

        bottom.state = BottomSheetBehavior.STATE_COLLAPSED

    }

    private fun setupMonet() {
        val secondaryBackground = monet.getBackgroundColorSecondary(this)
        val background = monet.getBackgroundColor(this)
        binding.bottomSheetContainer.search.searchBox.backgroundTintList = ColorStateList.valueOf(secondaryBackground!!)
        val root =  binding.bottomSheetContainer.root.background as GradientDrawable
        root.setColor(ColorUtils.setAlphaComponent(headerBackground,235))
        binding.getlocationContainer.backgroundTintList = ColorStateList.valueOf(background)
        binding.favourite.backgroundTintList = ColorStateList.valueOf(background)

    }



    private fun setUpNavigationView() {
        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){

                R.id.get_favourite -> {
                    openFavouriteListDialog()
                }
                R.id.settings -> {
                    startActivity(Intent(this,SettingsActivity::class.java))
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }

    }


    private fun initializeMap() {
        Configuration.getInstance().setUserAgentValue(getPackageName());
        map = binding.mapContainer
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Add these configuration lines
        map.isTilesScaledToDpi = true
        map.setUseDataConnection(true)  // Enable downloading tiles
        map.setMaxZoomLevel(22.0)  // Most zoomed out
        map.setMinZoomLevel(1.0) // Most zoomed in
        
        // Set up the map
        val mapController = map.controller
        mGeoPoint = GeoPoint(viewModel.getLat, viewModel.getLng)
        mapController.setZoom(16.0)
        mapController.setCenter(mGeoPoint)

        // Set up marker
        mMarker = Marker(map).apply {
            position = mGeoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Lat: ${mGeoPoint?.latitude}, Lon: ${mGeoPoint?.longitude}"
        }

        // Handle map clicks
        map.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                if (e != null && mapView != null) {
                    val proj = mapView.projection
                    val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt())
                    // Convert IGeoPoint to GeoPoint
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

    private fun isModuleEnable(){
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
            marker.title = "Lat: ${geoPoint.latitude}, Lon: ${geoPoint.longitude}"
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
        if (moveNewLocation) {
            mGeoPoint = GeoPoint(lat, lon)
            mGeoPoint?.let { geoPoint ->
                map.controller.animateTo(geoPoint)
                map.controller.setZoom(12.0)
                mMarker?.position = geoPoint
                mMarker?.title = "Lat: ${geoPoint.latitude}, Lon: ${geoPoint.longitude}"
                if (!map.overlays.contains(mMarker)) {
                    map.overlays.add(mMarker)
                }
                map.invalidate()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        map.onResume()
        viewModel.updateXposedState()
    }



    private fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  tittle = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            tittle.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }



    private fun addFavouriteDialog(){
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog_layout,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (!map.overlays.contains(mMarker)) {
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(this@MapActivity){
                        if (it == (-1).toLong()) showToast(getString(R.string.cant_save)) else showToast(getString(R.string.save))
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
        val view = layoutInflater.inflate(R.layout.fav,null)
        val  rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavourite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

    }


    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }


    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(R.string.update_available)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update_button)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(this@MapActivity)
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(this@MapActivity, it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }

                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    this@MapActivity,
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(this@MapActivity, it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun updateChecker(){
        lifecycleScope.launchWhenResumed {
            viewModel.update.collect{
                if (it!= null){
                    updateDialog()
                }
            }
        }
    }


    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                delay(3000)
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
            }else {
                val geocoder = Geocoder(this@MapActivity)
                val addressList: List<Address>? = geocoder.getFromLocationName(address,3)

                try {
                    addressList?.let {
                        if (it.size == 1){
                           trySend(SearchProgress.Complete(addressList[0].latitude, addressList[0].longitude))
                        }else {
                            trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                        }
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }

        awaitClose { this.cancel() }
    }




    private fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }

    }


    private fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }


    // Get current location
    @SuppressLint("MissingPermission")
    private fun getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                fusedLocationClient.lastLocation.addOnCompleteListener(this) { task ->
                    val location: Location? = task.result
                    if (location == null) {
                        requestNewLocationData()
                    } else {
                        lat = location.latitude
                        lon = location.longitude
                        moveMapToNewLocation(true)
                    }
                }
            } else {
                showToast("Turn on location")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }


    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation!!
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
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

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}


sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}