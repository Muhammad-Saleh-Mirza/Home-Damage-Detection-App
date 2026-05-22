package com.example.fixup.vendor

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.R
import com.example.fixup.auth.LoginActivity
import com.example.fixup.shared.ChatActivity
import com.example.fixup.shared.ChatbotActivity
import com.example.fixup.databinding.ActivityVendorDashboardBinding
import com.example.fixup.databinding.ItemVendorRequestBinding
import com.example.fixup.utils.Constants
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

class VendorDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVendorDashboardBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()
    private val uid  get() = auth.currentUser?.uid ?: ""

    private var currentVendorName = ""
    private var vendorLat: Double? = null
    private var vendorLon: Double? = null

    private val availableJobs = mutableListOf<VendorRequest>()
    private lateinit var availableAdapter: RequestsAdapter
    private var availableListener: ListenerRegistration? = null

    private val myJobs = mutableListOf<VendorRequest>()
    private lateinit var myJobsAdapter: RequestsAdapter
    private var myJobsListener: ListenerRegistration? = null

    data class VendorRequest(
        val id: String,
        val customerName: String,
        val title: String,
        val detectedClass: String,
        val detectedCategory: String,
        val priceMin: Int,
        val priceMax: Int,
        val status: String,
        val createdAt: Timestamp?,
        val lat: Double? = null,
        val lon: Double? = null,
        val distanceKm: Double? = null
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVendorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerViews()
        setupBottomNav()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logout, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
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
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        binding.progressBar.visibility = View.VISIBLE

        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                currentVendorName = doc.getString("name").orEmpty()
                val category = doc.getString("serviceCategory").orEmpty()
                vendorLat = doc.getDouble("latitude")
                vendorLon = doc.getDouble("longitude")
                if (currentVendorName.isNotEmpty()) {
                    binding.tvVendorGreeting.text = "Hello, $currentVendorName!"
                }
                if (category.isNotEmpty()) {
                    binding.tvVendorCategory.text = "📂 $category"
                }
                binding.progressBar.visibility = View.GONE

                if (category.isEmpty()) {
                    binding.tvEmptyAvailable.text = "Service category not set. Contact admin."
                    binding.emptyAvailableLayout.visibility = View.VISIBLE
                    return@addOnSuccessListener
                }
                startAvailableJobsListener(category)
                startMyJobsListener()
            }
            .addOnFailureListener {
                binding.progressBar.visibility = View.GONE
                binding.tvEmptyAvailable.text = "Failed to load profile. Try again."
                binding.emptyAvailableLayout.visibility = View.VISIBLE
            }
    }

    override fun onStop() {
        super.onStop()
        availableListener?.remove()
        myJobsListener?.remove()
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_available -> {
                    binding.tabAvailable.visibility = View.VISIBLE
                    binding.tabMyJobs.visibility    = View.GONE
                    true
                }
                R.id.nav_myjobs -> {
                    binding.tabAvailable.visibility = View.GONE
                    binding.tabMyJobs.visibility    = View.VISIBLE
                    true
                }
                R.id.nav_chatbot -> {
                    startActivity(Intent(this, ChatbotActivity::class.java))
                    false
                }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_available
    }

    private fun setupRecyclerViews() {
        availableAdapter = RequestsAdapter(
            items       = availableJobs,
            onItemClick = { req -> openDetail(req.id) }
        )
        binding.recyclerAvailable.layoutManager = LinearLayoutManager(this)
        binding.recyclerAvailable.adapter = availableAdapter

        myJobsAdapter = RequestsAdapter(
            items            = myJobs,
            onItemClick      = { req -> openDetail(req.id) },
            onChatClick      = { req -> openChat(req) },
            onMarkComplete   = { req -> confirmMarkComplete(req) }
        )
        binding.recyclerMyJobs.layoutManager = LinearLayoutManager(this)
        binding.recyclerMyJobs.adapter = myJobsAdapter
    }

    // ── Firestore listeners ────────────────────────────────────────────────────

    private fun startAvailableJobsListener(category: String) {
        availableListener?.remove()
        availableListener = db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("status", "open")
            .whereEqualTo("detectedCategory", category)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener

                val allRequests = snapshot.documents.map { it.toVendorRequest() }
                val vLat = vendorLat
                val vLon = vendorLon

                val filtered = if (vLat != null && vLon != null) {
                    allRequests.mapNotNull { req ->
                        val rLat = req.lat
                        val rLon = req.lon
                        if (rLat == null || rLon == null) {
                            req  // legacy request without location — include without badge
                        } else {
                            val dist = haversine(vLat, vLon, rLat, rLon)
                            if (dist <= Constants.MAX_DISTANCE_KM) req.copy(distanceKm = dist) else null
                        }
                    }
                } else {
                    allRequests  // vendor has no location — show all
                }

                availableJobs.clear()
                availableJobs.addAll(filtered)
                availableAdapter.notifyDataSetChanged()

                binding.emptyAvailableLayout.visibility =
                    if (availableJobs.isEmpty()) View.VISIBLE else View.GONE
                binding.tvLocationBanner.visibility =
                    if (vLat == null) View.VISIBLE else View.GONE
            }
    }

    private fun startMyJobsListener() {
        myJobsListener?.remove()
        myJobsListener = db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("acceptedVendorId", uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                myJobs.clear()
                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: continue
                    if (status == "assigned" || status == "completed") {
                        myJobs.add(doc.toVendorRequest())
                    }
                }
                myJobs.sortByDescending { it.createdAt?.seconds ?: 0L }
                myJobsAdapter.notifyDataSetChanged()
                binding.emptyMyJobsLayout.visibility =
                    if (myJobs.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun openDetail(requestId: String) {
        startActivity(Intent(this, RequestDetailActivity::class.java).apply {
            putExtra("requestId", requestId)
        })
    }

    private fun openChat(req: VendorRequest) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("requestId",  req.id)
            putExtra("vendorId",   uid)
            putExtra("vendorName", currentVendorName)
        })
    }

    // ── Mark job complete ──────────────────────────────────────────────────────

    private fun confirmMarkComplete(req: VendorRequest) {
        AlertDialog.Builder(this)
            .setTitle("Mark Job Complete?")
            .setMessage("Are you sure you want to mark \"${req.title}\" as complete? The customer will be notified and can rate your service.")
            .setPositiveButton("Yes, Complete") { _, _ -> doMarkComplete(req) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doMarkComplete(req: VendorRequest) {
        db.collection(Constants.COLLECTION_REQUESTS).document(req.id)
            .update(
                mapOf(
                    "status"      to "completed",
                    "completedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                AlertDialog.Builder(this)
                    .setTitle("Job Complete")
                    .setMessage("The job has been marked as complete. The customer can now rate your service.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Shared adapter (both tabs) ─────────────────────────────────────────────

    private inner class RequestsAdapter(
        private val items: List<VendorRequest>,
        private val onItemClick: (VendorRequest) -> Unit,
        private val onChatClick: ((VendorRequest) -> Unit)? = null,
        private val onMarkComplete: ((VendorRequest) -> Unit)? = null
    ) : RecyclerView.Adapter<RequestsAdapter.VH>() {

        inner class VH(val b: ItemVendorRequestBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemVendorRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val req = items[position]
            with(holder.b) {
                tvCustomerName.text = "👤 ${req.customerName}"
                tvTitle.text        = req.title

                val label = req.detectedClass.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                tvDamage.text     = "$label · ${req.detectedCategory}"
                tvPriceRange.text = "💰 PKR ${String.format(Locale.US, "%,d", req.priceMin)}" +
                        " – ${String.format(Locale.US, "%,d", req.priceMax)}"

                val dist = req.distanceKm
                if (dist != null) {
                    tvDistance.text = "📍 ${String.format("%.1f", dist)} km away"
                    tvDistance.visibility = View.VISIBLE
                } else {
                    tvDistance.visibility = View.GONE
                }

                tvDate.text       = req.createdAt?.toDate()?.let {
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(it)
                } ?: ""

                when (req.status) {
                    "assigned", "completed" -> {
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text       = req.status.replaceFirstChar(Char::uppercase)
                        tvStatus.background = GradientDrawable().apply {
                            shape        = GradientDrawable.RECTANGLE
                            cornerRadius = 12f
                            setColor(
                                if (req.status == "assigned") Color.parseColor("#FF9800")
                                else Color.parseColor("#9E9E9E")
                            )
                        }
                    }
                    else -> tvStatus.visibility = View.GONE
                }

                val isAssigned = req.status == "assigned"

                val showChat = onChatClick != null && isAssigned
                btnOpenChat.visibility = if (showChat) View.VISIBLE else View.GONE
                btnOpenChat.setOnClickListener { onChatClick?.invoke(req) }

                val showComplete = onMarkComplete != null && isAssigned
                btnMarkComplete.visibility = if (showComplete) View.VISIBLE else View.GONE
                btnMarkComplete.setOnClickListener { onMarkComplete?.invoke(req) }

                root.setOnClickListener { onItemClick(req) }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun DocumentSnapshot.toVendorRequest() = VendorRequest(
        id               = id,
        customerName     = getString("customerName") ?: "",
        title            = getString("title") ?: "",
        detectedClass    = getString("detectedClass") ?: "",
        detectedCategory = getString("detectedCategory") ?: "",
        priceMin         = getLong("estimatedPriceMin")?.toInt() ?: 0,
        priceMax         = getLong("estimatedPriceMax")?.toInt() ?: 0,
        status           = getString("status") ?: "",
        createdAt        = getTimestamp("createdAt"),
        lat              = getDouble("latitude"),
        lon              = getDouble("longitude")
    )

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
