package com.example.fixup.customer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.fixup.databinding.ActivityRequestStatusBinding
import com.example.fixup.shared.TrackingActivity
import com.example.fixup.utils.Constants
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Locale

class RequestStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestStatusBinding
    private val db = FirebaseFirestore.getInstance()

    private lateinit var requestId: String
    private var statusListener: ListenerRegistration? = null

    private var acceptedVendorId   = ""
    private var acceptedVendorName = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.example.fixup.utils.LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }

        binding.btnTrackVendor.setOnClickListener { openTrackingActivity() }
    }

    override fun onStart() {
        super.onStart()
        startStatusListener()
    }

    override fun onStop() {
        super.onStop()
        statusListener?.remove()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun startStatusListener() {
        binding.progressBar.visibility  = View.VISIBLE
        binding.layoutContent.visibility = View.GONE

        statusListener = db.collection(Constants.COLLECTION_REQUESTS)
            .document(requestId)
            .addSnapshotListener { doc, _ ->
                binding.progressBar.visibility  = View.GONE
                if (doc == null || !doc.exists()) { finish(); return@addSnapshotListener }

                binding.layoutContent.visibility = View.VISIBLE

                val imageUrl = doc.getString("imageUrl") ?: ""
                if (imageUrl.isNotEmpty()) {
                    Glide.with(this).load(imageUrl).centerCrop().into(binding.ivDamagePhoto)
                }

                binding.tvTitle.text       = doc.getString("title") ?: ""
                binding.tvCity.text        = "📍 ${doc.getString("city") ?: Constants.DEFAULT_CITY}"
                binding.tvDescription.text = doc.getString("description") ?: ""

                val date = doc.getTimestamp("createdAt")?.toDate()
                binding.tvDate.text = if (date != null)
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
                else ""

                val status         = doc.getString("status") ?: "open"
                val isVendorEnRoute = doc.getBoolean("isVendorEnRoute") ?: false

                binding.tvStatus.text = status.replaceFirstChar(Char::uppercase)
                val statusColor = when (status) {
                    "open"      -> Color.parseColor("#4CAF50")
                    "assigned"  -> Color.parseColor("#FF9800")
                    "completed" -> Color.parseColor("#9E9E9E")
                    else        -> Color.parseColor("#4CAF50")
                }
                binding.tvStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(statusColor)
                }

                acceptedVendorId   = doc.getString("acceptedVendorId")   ?: ""
                acceptedVendorName = doc.getString("acceptedVendorName") ?: ""
                if (acceptedVendorName.isNotEmpty() && status != "open") {
                    binding.tvAssignedTo.text = "👷 Assigned to: $acceptedVendorName"
                    binding.tvAssignedTo.visibility = View.VISIBLE
                } else {
                    binding.tvAssignedTo.visibility = View.GONE
                }

                // Show Track Vendor button when assigned AND vendor has started journey
                binding.btnTrackVendor.visibility =
                    if (status == "assigned" && isVendorEnRoute) View.VISIBLE else View.GONE

                // AI analysis
                val detected = doc.getString("detectedClass") ?: ""
                val service  = doc.getString("detectedCategory") ?: ""
                val conf     = doc.getDouble("detectedConfidence") ?: 0.0
                val confPct  = (conf * 100).toInt()
                val label    = detected.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

                binding.tvDetectedClass.text   = "$label Detected"
                binding.tvDetectedService.text = "Suggested Service: $service"
                binding.tvConfidence.text      = "$confPct%"
                binding.confidenceBar.progress = confPct

                val priceMin = doc.getLong("estimatedPriceMin")?.toInt() ?: 0
                val priceMax = doc.getLong("estimatedPriceMax")?.toInt() ?: 0
                val priceRec = doc.getLong("estimatedPriceRecommended")?.toInt() ?: 0
                val fmt      = "%,d"
                binding.tvPriceMin.text         = "PKR ${String.format(Locale.US, fmt, priceMin)}"
                binding.tvPriceMax.text         = "PKR ${String.format(Locale.US, fmt, priceMax)}"
                binding.tvPriceRecommended.text = "PKR ${String.format(Locale.US, fmt, priceRec)}"
                binding.tvPriceReason.text      = "\"${doc.getString("priceJustification") ?: ""}\""
            }
    }

    @SuppressLint("MissingPermission")
    private fun openTrackingActivity() {
        fun launch(lat: Double, lon: Double) {
            startActivity(Intent(this, TrackingActivity::class.java).apply {
                putExtra("requestId",         requestId)
                putExtra("vendorId",          acceptedVendorId)
                putExtra("vendorName",        acceptedVendorName.ifEmpty { "Vendor" })
                putExtra("customerLatitude",  lat)
                putExtra("customerLongitude", lon)
            })
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc -> launch(loc?.latitude ?: 0.0, loc?.longitude ?: 0.0) }
                .addOnFailureListener { launch(0.0, 0.0) }
        } else {
            launch(0.0, 0.0)
        }
    }
}
