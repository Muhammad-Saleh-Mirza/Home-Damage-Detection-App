package com.example.fixup.admin

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.fixup.R
import com.example.fixup.auth.LoginActivity
import com.example.fixup.databinding.ActivityAdminDashboardBinding
import com.example.fixup.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminDashboardBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.cardManageVendors.setOnClickListener {
            startActivity(Intent(this, ManageVendorsActivity::class.java))
        }
        binding.cardAllRequests.setOnClickListener {
            startActivity(Intent(this, AllRequestsActivity::class.java))
        }
        binding.btnSeedData.setOnClickListener { seedDemoData() }
    }

    override fun onStart() {
        super.onStart()
        loadStats()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logout, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
            confirmLogout()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── Stats ──────────────────────────────────────────────────────────────────

    private fun loadStats() {
        binding.progressBar.visibility = View.VISIBLE

        db.collection(Constants.COLLECTION_USERS).get()
            .addOnSuccessListener { snap -> binding.tvTotalUsers.text = snap.size().toString() }

        db.collection(Constants.COLLECTION_USERS)
            .whereEqualTo("role", Constants.ROLE_VENDOR)
            .whereEqualTo("isActive", true).get()
            .addOnSuccessListener { snap -> binding.tvActiveVendors.text = snap.size().toString() }

        db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("status", "open").get()
            .addOnSuccessListener { snap -> binding.tvOpenRequests.text = snap.size().toString() }

        db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("status", "completed").get()
            .addOnSuccessListener { snap ->
                binding.tvCompletedJobs.text = snap.size().toString()
                binding.progressBar.visibility = View.GONE
            }
            .addOnFailureListener { binding.progressBar.visibility = View.GONE }
    }

    // ── Logout ─────────────────────────────────────────────────────────────────

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Seed demo data ─────────────────────────────────────────────────────────

    private fun seedDemoData() {
        binding.btnSeedData.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val seeds = listOf(
            hashMapOf(
                "customerId" to "demo_customer_1", "customerName" to "Ahmed Khan",
                "title" to "Crack in living room wall",
                "description" to "Large crack running from corner to ceiling",
                "imageUrl" to "", "detectedClass" to "wall_crack",
                "detectedCategory" to "Plastering / Mason", "detectedConfidence" to 0.87,
                "estimatedPriceMin" to 1200L, "estimatedPriceMax" to 2800L,
                "estimatedPriceRecommended" to 1800L,
                "priceJustification" to "Medium severity crack, 1-2 hours labor",
                "priceSampleCount" to 24L, "city" to "Karachi", "status" to "open",
                "acceptedBidId" to "", "acceptedVendorId" to "",
                "createdAt" to FieldValue.serverTimestamp()
            ),
            hashMapOf(
                "customerId" to "demo_customer_2", "customerName" to "Sara Ali",
                "title" to "Peeling paint in bedroom",
                "description" to "Paint peeling off from most of the bedroom wall",
                "imageUrl" to "", "detectedClass" to "peeling_paint",
                "detectedCategory" to "Painter", "detectedConfidence" to 0.91,
                "estimatedPriceMin" to 3500L, "estimatedPriceMax" to 8000L,
                "estimatedPriceRecommended" to 5500L,
                "priceJustification" to "Full room repaint required, 2 coats",
                "priceSampleCount" to 9L, "city" to "Karachi", "status" to "open",
                "acceptedBidId" to "", "acceptedVendorId" to "",
                "createdAt" to FieldValue.serverTimestamp()
            ),
            hashMapOf(
                "customerId" to "demo_customer_3", "customerName" to "Usman Malik",
                "title" to "Damaged electric socket",
                "description" to "Burned socket in kitchen, sparks visible",
                "imageUrl" to "", "detectedClass" to "electrical_fault",
                "detectedCategory" to "Electrician", "detectedConfidence" to 0.79,
                "estimatedPriceMin" to 600L, "estimatedPriceMax" to 2000L,
                "estimatedPriceRecommended" to 1200L,
                "priceJustification" to "Socket replacement + safety check",
                "priceSampleCount" to 18L, "city" to "Karachi", "status" to "open",
                "acceptedBidId" to "", "acceptedVendorId" to "",
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        val batch = db.batch()
        seeds.forEach { data ->
            batch.set(db.collection(Constants.COLLECTION_REQUESTS).document(), data)
        }

        batch.commit()
            .addOnSuccessListener {
                binding.btnSeedData.isEnabled = true
                binding.progressBar.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle("Demo Data Added")
                    .setMessage("3 sample repair requests have been created for the demo.")
                    .setPositiveButton("OK", null)
                    .show()
                loadStats()
            }
            .addOnFailureListener { e ->
                binding.btnSeedData.isEnabled = true
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Seed failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
