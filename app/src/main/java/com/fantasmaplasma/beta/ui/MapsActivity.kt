package com.fantasmaplasma.beta.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.fantasmaplasma.beta.adapter.MarkerClusterRenderer
import com.fantasmaplasma.beta.R
import com.fantasmaplasma.beta.adapter.MarkerInfoWindowAdapter
import com.fantasmaplasma.beta.adapter.RouteClusterAdapter
import com.fantasmaplasma.beta.data.Route
import com.fantasmaplasma.beta.databinding.ActivityMapsBinding
import com.fantasmaplasma.beta.databinding.DrawerClusterRouteBinding
import com.fantasmaplasma.beta.databinding.DrawerSingleRouteBinding
import com.fantasmaplasma.beta.ui.route.RouteInfoActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterManager

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, ClusterManager.OnClusterClickListener<Route>, ClusterManager.OnClusterItemClickListener<Route> {
    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var mClusterManager: ClusterManager<Route>
    private lateinit var mViewModel: MapsViewModel
    private lateinit var mBinding: ActivityMapsBinding
    private var mNavDrawerSingle: DrawerSingleRouteBinding? = null
    private var mNavDrawerCluster: DrawerClusterRouteBinding? = null
    private var mMap: GoogleMap? = null

    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1
        const val ZOOM_RANGE_NEW_ROUTE = 35f
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ROUTE_TYPE = "extra_route_type"
        const val RC_SIGN_IN = 100
        var draggableRouteMarker: View? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        mBinding.dlMap.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        initViewModel()
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun initViewModel() {
        mViewModel = ViewModelProvider(this).get(MapsViewModel::class.java)
        mViewModel.routesLiveData.observe(this) { clusterItems ->
            mMap ?: return@observe
            mClusterManager.clearItems()
            mClusterManager.addItems(clusterItems)
        }
    }

    override fun onResume() {
        super.onResume()
        if (draggableRouteMarker?.visibility == View.INVISIBLE)
            removeNewRouteView()
        mViewModel.requestRoutesData()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mClusterManager = ClusterManager(this, googleMap)
        mClusterManager.setOnClusterClickListener(this)
        mClusterManager.renderer =
            MarkerClusterRenderer(this, googleMap, mClusterManager)
        mClusterManager.setOnClusterItemClickListener(this)
        mViewModel.requestRoutesData()
        mMap = googleMap.apply {
            setOnMapLongClickListener { location ->
                showAddLocationDialog(location)
            }
            setOnCameraIdleListener(mClusterManager)
            setOnMarkerClickListener(mClusterManager)
            stopAnimation()
            animateCamera(CameraUpdateFactory.zoomTo(minZoomLevel))
            setInfoWindowAdapter(MarkerInfoWindowAdapter(this@MapsActivity))
            try {
                setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(
                        this@MapsActivity,
                        R.raw.map_style
                    )
                )
            } catch (e: Exception) {}
        }
        requestMoveToCurrentLocationOnMap()
    }

    override fun onClusterItemClick(item: Route?): Boolean {
        item?.let { marker ->
            initNavDrawer(isSingleItem = true)
            setSingleItemInfo(marker)
            openSlidingDrawer()
            return true
        }
        return false
    }

    private fun setSingleItemInfo(route: Route) {
        mNavDrawerSingle?.apply {
            tvNavViewRouteGrade.text = route.betaScale.toString()
            tvNavViewRouteName.text = route.name
            tvNavViewRouteType.text = route.type.toString()
            btnNavViewShowMore.setOnClickListener {
                startActivity(
                    Intent(this@MapsActivity, RouteInfoActivity::class.java)
                )
            }
            root.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    root.removeOnLayoutChangeListener(this)
                    setVisibleFromDrawer(route.position)
                }
            })
        }
    }

    override fun onClusterClick(cluster: Cluster<Route>?): Boolean {
        cluster?.let { marker ->
            initNavDrawer(isSingleItem = false)
            setClusterItemInfo(marker)
            setVisibleFromDrawer(marker.position)
            openSlidingDrawer()
            return true
        }
        return false
    }

    private fun setClusterItemInfo(routes: Cluster<Route>) {
        val context: Context = this
        mNavDrawerCluster?.rvDrawerRouteCluster?.apply {
            adapter = RouteClusterAdapter(context, routes) { selectedRoute ->
                maxZoomIn()
                onClusterItemClick(selectedRoute)
            }
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }
        mNavDrawerCluster?.apply {
            root.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    root.removeOnLayoutChangeListener(this)
                    setVisibleFromDrawer(routes.position)
                }
            })
        }
    }

    private fun maxZoomIn() {
        mMap?.apply {
            animateCamera(CameraUpdateFactory.zoomTo(maxZoomLevel))
        }
    }

    /**
     * Move the map to view marker from the far right side of screen.
     * @param latLng Position of marker clicked.
     */
    private fun setVisibleFromDrawer(latLng: LatLng) {
        mMap?.apply {
            val startLatLng = projection.fromScreenLocation(
                Point(
                    0,
                    mBinding.containerMap.height/2
                )
            )
            val endXPos = (mNavDrawerSingle?.root?.width ?: mNavDrawerCluster?.root?.width ?: 0)
            val endLatLng = projection.fromScreenLocation(
                Point(
                    endXPos,
                    mBinding.containerMap.height/2
                )
            )
            val newLatLng = LatLng(
                latLng.latitude,
                latLng.longitude - (endLatLng.longitude - startLatLng.longitude)/2.0
            )
            animateCamera(
                CameraUpdateFactory.newLatLng(newLatLng)
            )
        }
    }

    private fun initNavDrawer(isSingleItem: Boolean) {
        mBinding.nvMapRouteInfo.apply {
            if(isSingleItem && mNavDrawerSingle == null) {
                mNavDrawerSingle = DrawerSingleRouteBinding.inflate(layoutInflater)
                mNavDrawerCluster?.root?.let { drawerClusterView ->
                    removeHeaderView(drawerClusterView)
                }
                mNavDrawerCluster = null
                addHeaderView(mNavDrawerSingle!!.root)
            } else if(!isSingleItem && mNavDrawerCluster == null) {
                mNavDrawerCluster = DrawerClusterRouteBinding.inflate(layoutInflater)
                mNavDrawerSingle?.root?.let { drawerSingleView ->
                    removeHeaderView(drawerSingleView)
                }
                mNavDrawerSingle = null
                addHeaderView(mNavDrawerCluster!!.root)
            }
        }
    }

    private fun openSlidingDrawer() {
        mBinding.dlMap.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        mBinding.dlMap.openDrawer(GravityCompat.START)
    }

    private fun requestMoveToCurrentLocationOnMap() {
        val permissions = arrayOf (
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat
                    .requestPermissions(
                        this,
                        permissions,
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                break
            } else {
                mMap?.isMyLocationEnabled = true
                mFusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
                    moveMapOverLocation(location)
                }
            }
        }
    }

    private fun moveMapOverLocation(location: Location?) {
        location?.let {
            val latLng = LatLng(it.latitude, it.longitude)
            val cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, 10f)
            mMap?.animateCamera(cameraUpdate)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()) {
            for (result in grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    requestMoveToCurrentLocationOnMap()
                    return
                }
            }
            Toast.makeText(this, getString(R.string.location_disabled), Toast.LENGTH_SHORT)
                .show()
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.maps, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        when (item.itemId) {
            R.id.menu_location_add -> {
                showAddLocationDialog()
                true
            }
            R.id.menu_satellite_map -> {
                mMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
                true
            }
            R.id.menu_normal_map -> {
                mMap?.mapType = GoogleMap.MAP_TYPE_NORMAL
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun showAddLocationDialog(location: LatLng? = null) {
        Dialog(this).apply {
            setContentView(R.layout.dialog_location_add)

            val btnClickListener = View.OnClickListener {btn ->
                createDraggableMarker(
                    when (btn.id) {
                        R.id.btn_dialog_location_bouldering ->
                            Route.BOULDERING
                        R.id.btn_dialog_location_sport ->
                            Route.SPORT
                        R.id.btn_dialog_location_trad ->
                            Route.TRAD
                        R.id.btn_dialog_location_alpine ->
                            Route.ALPINE
                        else ->
                            throw Exception("Invalid id of ${btn.id} route in add location dialog.")
                    }
                )
                location?.let {
                    draggableRouteMarker?.apply {
                        val point = mMap?.projection?.toScreenLocation(it) ?: return@let
                        x = point.x.toFloat() + width/2f
                        y = point.y.toFloat() + height
                    }
                }
                cancel()
            }

           findViewById<LinearLayout>(R.id.ll_dialog_location_btns)
               .children.iterator().forEach { routeBtn ->
                   routeBtn.setOnClickListener(btnClickListener)
               }

            show()
        }
    }

    private fun createDraggableMarker(routeType: Int) {
        if (mMap == null) {
            Toast.makeText(
                this,
                getString(R.string.load_map_before_adding_route),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        removeNewRouteView()
        val container = mBinding.containerMap
        draggableRouteMarker =
            layoutInflater.inflate(R.layout.layout_map_new_route, container, false)
                .apply {

                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        x = container.width / 2f - width / 2
                        y = container.height / 2f - height / 2
                    }

                    setAddRouteMarkerTouchListener()

                    setAddRouteMarkerImage(routeType)

                    findViewById<View>(R.id.img_btn_route_remove)
                        .setOnClickListener {
                            removeNewRouteView()
                        }
                    findViewById<View>(R.id.img_btn_route_add)
                        .setOnClickListener {
                            val latLng = getLatLngFromLayoutCords(
                                x + width / 2f,
                                y + height
                            ) ?: return@setOnClickListener
                            animateMarkerToGoogleMarkerToAddRouteActivity(latLng, routeType)
                        }

                    container.addView(
                        this,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
            }
    }

    private fun View.setAddRouteMarkerImage(routeType: Int) {
        findViewById<ImageView>(R.id.iv_new_route_type)
            .setImageDrawable(
                ContextCompat.getDrawable(
                    this@MapsActivity,
                    when (routeType) {
                        Route.BOULDERING ->
                            R.drawable.img_marker_boulder
                        Route.SPORT ->
                            R.drawable.img_marker_sport
                        Route.TRAD ->
                            R.drawable.img_marker_trad
                        Route.ALPINE ->
                            R.drawable.img_marker_alpine
                        else ->
                            throw Exception("Drawable does not exist for route.")
                    }
                )
            )
    }

    private fun View.setAddRouteMarkerTouchListener() {
        var startX = -1f
        var startY = -1f
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                }
                else -> {
                    val addX = event.x - startX
                    val addY = event.y - startY
                    x += addX
                    y += addY
                }
            }
            return@setOnTouchListener true
        }
    }

    private fun removeNewRouteView() {
        draggableRouteMarker ?: return
        mBinding.containerMap.removeView(draggableRouteMarker)
        draggableRouteMarker = null
    }

    private fun getLatLngFromLayoutCords(mouseX: Float, mouseY: Float): LatLng? {
        val map = mMap ?: return null
        val smallestPossibleZoom = map.maxZoomLevel - ZOOM_RANGE_NEW_ROUTE
        if (map.cameraPosition.zoom <= smallestPossibleZoom) {
            Toast.makeText(this, getString(R.string.please_zoom_in), Toast.LENGTH_SHORT)
                .show()
            return null
        }
        return map.projection.fromScreenLocation(Point(mouseX.toInt(), mouseY.toInt()))
    }

    private fun animateMarkerToGoogleMarkerToAddRouteActivity(latLng: LatLng, routeType: Int) {
        mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
        )
        draggableRouteMarker?.apply {
            animate()
                .alpha(0f)
                .setDuration(1000)
                .withEndAction {
                    visibility = View.INVISIBLE
                    startAddRouteActivity(latLng, routeType)
                }
                .start()
        }
    }

    private fun startAddRouteActivity(latLng: LatLng, routeType: Int) {
        startActivity(
            Intent(this, AddRouteActivity::class.java).apply {
                putExtra(EXTRA_LATITUDE, latLng.latitude)
                putExtra(EXTRA_LONGITUDE, latLng.longitude)
                putExtra(EXTRA_ROUTE_TYPE, routeType)
            }
        )
    }

/*    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val response = IdpResponse.fromResultIntent(data)
            if(resultCode == RESULT_OK) {
                val user = FirebaseAuth.getInstance().currentUser
            } else {

            }
        }
    }*/

}