package com.example.fixup.customer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.fixup.databinding.ActivityRequestStatusBinding
import com.example.fixup.utils.Constants
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class RequestStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestStatusBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
        loadRequest(requestId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun loadRequest(requestId: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutContent.visibility = View.GONE

        db.collection(Constants.COLLECTION_REQUESTS).document(requestId).get()
            .addOnSuccessListener { doc ->
                binding.progressBar.visibility = View.GONE
                if (!doc.exists()) { finish(); return@addOnSuccessListener }

                binding.layoutContent.visibility = View.VISIBLE

                // Photo
                val imageUrl = doc.getString("imageUrl") ?: ""
                if (imageUrl.isNotEmpty()) {
                    Glide.with(this)
                        .load(imageUrl)
                        .centerCrop()
                        .into(binding.ivDamagePhoto)
                }

                // Meta
                binding.tvTitle.text = doc.getString("title") ?: ""
                binding.tvCity.text  = "📍 ${doc.getString("city") ?: Constants.DEFAULT_CITY}"
                binding.tvDescription.text = doc.getString("description") ?: ""

                val date = doc.getTimestamp("createdAt")?.toDate()
                binding.tvDate.text = if (date != null)
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
                else ""

                val status = doc.getString("status") ?: "open"
                binding.tvStatus.text = status.replaceFirstChar(Char::uppercase)

                val vendorName = doc.getString("acceptedVendorName") ?: ""
                if (vendorName.isNotEmpty() && status != "open") {
                    binding.tvAssignedTo.text = "👷 Assigned to: $vendorName"
                    binding.tvAssignedTo.visibility = View.VISIBLE
                } else {
                    binding.tvAssignedTo.visibility = View.GONE
                }
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

                // AI result
                val detected  = doc.getString("detectedClass") ?: ""
                val service   = doc.getString("detectedCategory") ?: ""
                val conf      = doc.getDouble("detectedConfidence") ?: 0.0
                val confPct   = (conf * 100).toInt()
                val label     = detected.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

                binding.tvDetectedClass.text   = "$label Detected"
                binding.tvDetectedService.text = "Suggested Service: $service"
                binding.tvConfidence.text      = "$confPct%"
                binding.confidenceBar.progress = confPct

                val priceMin  = doc.getLong("estimatedPriceMin")?.toInt() ?: 0
                val priceMax  = doc.getLong("estimatedPriceMax")?.toInt() ?: 0
                val priceRec  = doc.getLong("estimatedPriceRecommended")?.toInt() ?: 0
                val fmt       = "%,d"
                binding.tvPriceMin.text         = "PKR ${String.format(Locale.US, fmt, priceMin)}"
                binding.tvPriceMax.text         = "PKR ${String.format(Locale.US, fmt, priceMax)}"
                binding.tvPriceRecommended.text = "PKR ${String.format(Locale.US, fmt, priceRec)}"
                binding.tvPriceReason.text      = "\"${doc.getString("priceJustification") ?: ""}\""
            }
            .addOnFailureListener { finish() }
    }
}
