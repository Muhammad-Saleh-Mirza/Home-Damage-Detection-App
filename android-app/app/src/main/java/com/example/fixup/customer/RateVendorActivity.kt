package com.example.fixup.customer

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.fixup.databinding.ActivityRateVendorBinding
import com.example.fixup.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class RateVendorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRateVendorBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private val uid get() = auth.currentUser?.uid ?: ""

    private lateinit var requestId: String
    private lateinit var vendorId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRateVendorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
        vendorId  = intent.getStringExtra("vendorId")  ?: run { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnSubmit.setOnClickListener { submitRating() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun submitRating() {
        val score = binding.ratingBar.rating.toInt()
        if (score == 0) {
            Toast.makeText(this, "Please select a star rating.", Toast.LENGTH_SHORT).show()
            return
        }

        val comment = binding.etComment.text.toString().trim()

        setLoading(true)

        // Step 1: save rating document
        val ratingDoc = hashMapOf(
            "requestId"  to requestId,
            "customerId" to uid,
            "vendorId"   to vendorId,
            "score"      to score,
            "comment"    to comment,
            "createdAt"  to FieldValue.serverTimestamp()
        )

        db.collection(Constants.COLLECTION_RATINGS)
            .add(ratingDoc)
            .addOnSuccessListener {
                updateVendorStats(score)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Failed to submit rating: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Step 2+3: recalculate average rating and increment totalJobs
    private fun updateVendorStats(newScore: Int) {
        db.collection(Constants.COLLECTION_RATINGS)
            .whereEqualTo("vendorId", vendorId)
            .get()
            .addOnSuccessListener { snapshot ->
                val scores = snapshot.documents.mapNotNull { it.getLong("score")?.toDouble() }
                val average = if (scores.isNotEmpty()) scores.average() else newScore.toDouble()
                val rounded = Math.round(average * 10.0) / 10.0  // one decimal place

                db.collection(Constants.COLLECTION_USERS).document(vendorId)
                    .update(
                        mapOf(
                            "rating"    to rounded,
                            "totalJobs" to FieldValue.increment(1)
                        )
                    )
                    .addOnSuccessListener {
                        setLoading(false)
                        AlertDialog.Builder(this)
                            .setTitle("Thank You!")
                            .setMessage("Your rating has been submitted. Your feedback helps other customers find the best vendors.")
                            .setPositiveButton("Done") { _, _ ->
                                startActivity(
                                    Intent(this, CustomerHomeActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                )
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    .addOnFailureListener { e ->
                        setLoading(false)
                        Toast.makeText(this, "Rating saved but profile update failed: ${e.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Rating saved but average calculation failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled    = !loading
        binding.ratingBar.setIsIndicator(loading)
        binding.etComment.isEnabled    = !loading
    }
}
