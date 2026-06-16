package com.example.fixup.shared

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fixup.R
import com.example.fixup.databinding.ActivityTrackingBinding
import com.example.fixup.utils.Constants
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale

class TrackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrackingBinding
    private val db = FirebaseFirestore.getInstance()

    private lateinit var requestId: String
    private var vendorName  = "Vendor"
    private var customerLat = 0.0
    private var customerLon = 0.0
    private var vendorLat   = 0.0
    private var vendorLon   = 0.0

    private var googleMap: GoogleMap? = null
    private var customerMarker: Marker? = null
    private var vendorMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var locationListener: ListenerRegistration? = null
    private var cameraInitialized = false
    private var customerLocationFetched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId   = intent.getStringExtra("requestId")        ?: run { finish(); return }
        vendorName  = intent.getStringExtra("vendorName")        ?: "Vendor"
        customerLat = intent.getDoubleExtra("customerLatitude",  0.0)
        customerLon = intent.getDoubleExtra("customerLongitude", 0.0)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tracking $vendorName"

        binding.tvVendorNameTracking.text = vendorName

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commit()
        mapFragment.getMapAsync { map ->
            googleMap = map
            setupInitialMap()
            startLocationListener()
        }

        binding.btnBackFromTracking.setOnClickListener { finish() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.remove()
    }

    @SuppressLint("MissingPermission")
    private fun setupInitialMap() {
        val map = googleMap ?: return
        map.uiSettings.isZoomControlsEnabled = true

        // Native blue dot/arrow showing customer's live GPS position
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        // If coords came via Intent, place job-location marker immediately
        if (customerLat != 0.0 || customerLon != 0.0) {
            placeCustomerMarker(customerLat, customerLon)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(customerLat, customerLon), 13f))
            customerLocationFetched = true
        }
    }

    private fun placeCustomerMarker(lat: Double, lon: Double) {
        val map = googleMap ?: return
        customerMarker?.remove()
        customerMarker = map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lon))
                .title("Job Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }

    private fun startLocationListener() {
        locationListener = db.collection(Constants.COLLECTION_REQUESTS)
            .document(requestId)
            .addSnapshotListener { doc, _ ->
                if (doc == null || !doc.exists()) return@addSnapshotListener

                // Read customer coordinates from request doc the first time
                if (!customerLocationFetched) {
                    val docLat = doc.getDouble("latitude")
                    val docLon = doc.getDouble("longitude")
                    if (docLat != null && docLon != null && (docLat != 0.0 || docLon != 0.0)) {
                        customerLat = docLat
                        customerLon = docLon
                        customerLocationFetched = true
                        placeCustomerMarker(customerLat, customerLon)
                        if (!cameraInitialized) {
                            googleMap?.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(customerLat, customerLon), 13f)
                            )
                        }
                    }
                }

                val newLat = doc.getDouble("vendorLatitude")  ?: return@addSnapshotListener
                val newLon = doc.getDouble("vendorLongitude") ?: return@addSnapshotListener
                if (newLat == 0.0 && newLon == 0.0) return@addSnapshotListener
                updateVendorPosition(newLat, newLon)
            }
    }

    private fun updateVendorPosition(newLat: Double, newLon: Double) {
        val map = googleMap ?: return
        val newLatLng = LatLng(newLat, newLon)

        if (vendorMarker == null) {
            vendorMarker = map.addMarker(
                MarkerOptions()
                    .position(newLatLng)
                    .title(vendorName)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
            if (!cameraInitialized) {
                cameraInitialized = true
                if (customerLat != 0.0 || customerLon != 0.0) {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(customerLat, customerLon))
                        .include(newLatLng)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                } else {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng, 14f))
                }
            }
        } else {
            val oldLatLng = vendorMarker!!.position
            animateMarker(vendorMarker!!, oldLatLng, newLatLng)
        }

        vendorLat = newLat
        vendorLon = newLon

        routePolyline?.remove()
        if (customerLat != 0.0 || customerLon != 0.0) {
            val pattern: List<PatternItem> = listOf(Dash(30f), Gap(20f))
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .add(LatLng(customerLat, customerLon), newLatLng)
                    .color(Color.parseColor("#FF5722"))
                    .width(6f)
                    .pattern(pattern)
            )
        }

        updateDistanceEta(newLat, newLon)
    }

    private fun animateMarker(marker: Marker, from: LatLng, to: LatLng) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f   = anim.animatedFraction
                val lat = from.latitude  + (to.latitude  - from.latitude)  * f
                val lng = from.longitude + (to.longitude - from.longitude) * f
                marker.position = LatLng(lat, lng)
            }
            start()
        }
    }

    private fun updateDistanceEta(vLat: Double, vLon: Double) {
        if (customerLat == 0.0 && customerLon == 0.0) {
            binding.tvDistanceEta.text = "Vendor is on the way"
            return
        }
        val results = FloatArray(1)
        Location.distanceBetween(customerLat, customerLon, vLat, vLon, results)
        val distKm  = results[0] / 1000.0
        val etaMins = ((distKm / 30.0) * 60).toInt().coerceAtLeast(1)
        binding.tvDistanceEta.text = String.format(
            Locale.US, "%.1f km away · approx %d min", distKm, etaMins
        )
    }
}
