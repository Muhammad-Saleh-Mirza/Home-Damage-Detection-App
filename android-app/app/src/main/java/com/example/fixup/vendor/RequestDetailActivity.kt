package com.example.fixup.vendor

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.fixup.databinding.ActivityRequestDetailBinding
import com.example.fixup.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class RequestDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestDetailBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private lateinit var requestId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadRequest()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Load & display request ─────────────────────────────────────────────────

    private fun loadRequest() {
        binding.progressBar.visibility  = View.VISIBLE
        binding.layoutContent.visibility = View.GONE

        db.collection(Constants.COLLECTION_REQUESTS).document(requestId).get()
            .addOnSuccessListener { doc ->
                binding.progressBar.visibility  = View.GONE
                binding.layoutContent.visibility = View.VISIBLE

                if (!doc.exists()) { finish(); return@addOnSuccessListener }

                val imageUrl         = doc.getString("imageUrl") ?: ""
                val customerName     = doc.getString("customerName") ?: ""
                val city             = doc.getString("city") ?: Constants.DEFAULT_CITY
                val description      = doc.getString("description") ?: ""
                val detectedClass    = doc.getString("detectedClass") ?: ""
                val detectedCategory = doc.getString("detectedCategory") ?: ""
                val confidence       = doc.getDouble("detectedConfidence") ?: 0.0
                val priceMin         = doc.getLong("estimatedPriceMin")?.toInt() ?: 0
                val priceMax         = doc.getLong("estimatedPriceMax")?.toInt() ?: 0
                val priceRec         = doc.getLong("estimatedPriceRecommended")?.toInt() ?: 0
                val priceReason      = doc.getString("priceJustification") ?: ""
                val sampleCount      = doc.getLong("priceSampleCount")?.toInt() ?: 0
                val createdAt        = doc.getTimestamp("createdAt")

                supportActionBar?.title = doc.getString("title") ?: "Request Detail"

                // Photo
                if (imageUrl.isNotEmpty()) {
                    Glide.with(this)
                        .load(imageUrl)
                        .centerCrop()
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .into(binding.ivDamagePhoto)
                }

                // Meta
                binding.tvCustomerName.text = "👤 $customerName"
                binding.tvCity.text         = "📍 $city"
                binding.tvDate.text         = createdAt?.toDate()?.let {
                    SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(it)
                } ?: ""
                binding.tvDescription.text  = description

                // AI result
                val label = detectedClass.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                binding.tvDetectedClass.text   = "$label Detected"
                binding.tvDetectedService.text = "Suggested Service: $detectedCategory"
                binding.tvConfidence.text      = "Confidence: ${(confidence * 100).toInt()}%"

                val fmt = "%,d"
                binding.tvPriceRange.text       = "💰 PKR ${String.format(Locale.US, fmt, priceMin)}" +
                        " – ${String.format(Locale.US, fmt, priceMax)}"
                binding.tvPriceRecommended.text = "Recommended: PKR ${String.format(Locale.US, fmt, priceRec)}"
                binding.tvPriceReason.text      = if (sampleCount > 0)
                    "\"$priceReason\" · Based on $sampleCount bids"
                else
                    "\"$priceReason\""

                checkAlreadyBid()
            }
            .addOnFailureListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to load request", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    // ── Bid guard ──────────────────────────────────────────────────────────────

    private fun checkAlreadyBid() {
        val uid = auth.currentUser?.uid ?: return
        db.collection(Constants.COLLECTION_BIDS)
            .whereEqualTo("requestId", requestId)
            .whereEqualTo("vendorId", uid)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    binding.layoutBidForm.visibility = View.GONE
                    binding.tvAlreadyBid.visibility  = View.VISIBLE
                } else {
                    binding.layoutBidForm.visibility = View.VISIBLE
                    binding.tvAlreadyBid.visibility  = View.GONE
                    binding.btnSubmitBid.setOnClickListener { submitBid() }
                }
            }
    }

    // ── Submit bid ─────────────────────────────────────────────────────────────

    private fun submitBid() {
        val uid = auth.currentUser?.uid ?: return

        val amount = binding.etAmount.text.toString().trim().toIntOrNull()
        val hours  = binding.etHours.text.toString().trim().toDoubleOrNull()
        val note   = binding.etNote.text.toString().trim()

        if (amount == null || amount <= 0) {
            binding.etAmount.error = "Enter a valid amount in PKR"
            return
        }
        if (hours == null || hours <= 0) {
            binding.etHours.error = "Enter estimated hours (e.g. 2 or 2.5)"
            return
        }

        setSubmitLoading(true)

        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                val vendorName     = doc.getString("name") ?: "Vendor"
                val vendorRating   = doc.getDouble("rating") ?: 0.0
                val vendorCategory = doc.getString("serviceCategory") ?: ""

                val bid = hashMapOf<String, Any?>(
                    "requestId"      to requestId,
                    "vendorId"       to uid,
                    "vendorName"     to vendorName,
                    "vendorRating"   to vendorRating,
                    "vendorCategory" to vendorCategory,
                    "amount"         to amount,
                    "estimatedHours" to hours,
                    "note"           to note,
                    "status"         to "pending",
                    "createdAt"      to FieldValue.serverTimestamp()
                )

                db.collection(Constants.COLLECTION_BIDS).add(bid)
                    .addOnSuccessListener {
                        setSubmitLoading(false)
                        AlertDialog.Builder(this)
                            .setTitle("Bid Submitted!")
                            .setMessage("Your bid of PKR ${String.format(java.util.Locale.US, "%,d", amount)} has been submitted. You will be notified if the customer accepts.")
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    .addOnFailureListener { e ->
                        setSubmitLoading(false)
                        Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                setSubmitLoading(false)
                Toast.makeText(this, "Failed to load vendor profile", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setSubmitLoading(loading: Boolean) {
        binding.btnSubmitBid.isEnabled    = !loading
        binding.etAmount.isEnabled        = !loading
        binding.etHours.isEnabled         = !loading
        binding.etNote.isEnabled          = !loading
        binding.progressBarBid.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
