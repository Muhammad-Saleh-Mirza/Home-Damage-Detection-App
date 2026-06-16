package com.example.fixup.vendor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.fixup.R
import com.example.fixup.auth.LoginActivity
import com.example.fixup.shared.ChatActivity
import com.example.fixup.shared.ChatbotActivity
import com.example.fixup.databinding.ActivityVendorDashboardBinding
import com.example.fixup.databinding.ItemVendorRequestBinding
import com.example.fixup.utils.Constants
import com.example.fixup.utils.LocaleHelper
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
    private var cachedProfilePicUrl = ""
    private var toolbarAvatarView: ImageView? = null

    private val availableJobs = mutableListOf<VendorRequest>()
    private lateinit var availableAdapter: RequestsAdapter
    private var availableListener: ListenerRegistration? = null
    private val knownJobIds = mutableSetOf<String>()
    private val newJobIds = mutableSetOf<String>()
    private var availableJobsInitialized = false

    private val myJobs = mutableListOf<VendorRequest>()
    private lateinit var myJobsAdapter: RequestsAdapter
    private var myJobsListener: ListenerRegistration? = null
    private var userListener: ListenerRegistration? = null
    private val knownMyJobIds = mutableSetOf<String>()
    private var myJobsInitialized = false

    data class VendorRequest(
        val id: String,
        val customerName: String,
        val title: String,
        val detectedClass: String,
        val detectedCategory: String,
        val priceMin: Int,
        val priceMax: Int,
        val acceptedBidAmount: Int,
        val status: String,
        val createdAt: Timestamp?,
        val lat: Double? = null,
        val lon: Double? = null,
        val distanceKm: Double? = null
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

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
        toolbarAvatarView = menu.findItem(R.id.action_profile_avatar)
            ?.actionView?.findViewById(R.id.ivAvatarToolbar)
        loadAvatarImage(cachedProfilePicUrl)
        return true
    }

    private fun loadAvatarImage(url: String) {
        cachedProfilePicUrl = url
        val iv = toolbarAvatarView ?: return
        Glide.with(this)
            .load(if (url.isNotEmpty()) url else R.drawable.ic_person_placeholder)
            .circleCrop()
            .placeholder(R.drawable.ic_person_placeholder)
            .error(R.drawable.ic_person_placeholder)
            .into(iv)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_language) {
            LocaleHelper.toggleAndApply(this)
            recreate()
            return true
        }
        if (item.itemId == R.id.action_logout) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_logout_title))
                .setMessage(getString(R.string.dialog_logout_msg))
                .setPositiveButton(getString(R.string.dialog_logout_title)) { _, _ ->
                    auth.signOut()
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        binding.progressBar.visibility = View.VISIBLE
        startUserListener()
    }

    override fun onStop() {
        super.onStop()
        userListener?.remove();    userListener    = null
        availableListener?.remove(); availableListener = null
        myJobsListener?.remove();  myJobsListener  = null
        myJobsInitialized = false
        knownMyJobIds.clear()
    }

    private fun startUserListener() {
        userListener?.remove()
        userListener = db.collection(Constants.COLLECTION_USERS).document(uid)
            .addSnapshotListener { doc, error ->
                if (error != null || doc == null || !doc.exists()) {
                    binding.progressBar.visibility = View.GONE
                    return@addSnapshotListener
                }
                val isActive = doc.getBoolean("isActive") ?: true
                if (!isActive) {
                    showSuspendedAndLogout()
                    return@addSnapshotListener
                }
                currentVendorName = doc.getString("name").orEmpty()
                val category = doc.getString("serviceCategory").orEmpty()
                vendorLat = doc.getDouble("latitude")
                vendorLon = doc.getDouble("longitude")
                if (currentVendorName.isNotEmpty()) {
                    binding.tvVendorGreeting.text = "Hello, $currentVendorName!"
                }
                loadAvatarImage(doc.getString("profilePictureUrl") ?: "")
                if (category.isNotEmpty()) {
                    binding.tvVendorCategory.text = "📂 $category"
                }
                binding.progressBar.visibility = View.GONE
                if (category.isEmpty()) {
                    binding.tvEmptyAvailable.text = "Service category not set. Contact admin."
                    binding.emptyAvailableLayout.visibility = View.VISIBLE
                    return@addSnapshotListener
                }
                if (availableListener == null) {
                    startAvailableJobsListener(category)
                    startMyJobsListener()
                }
            }
    }

    private fun showSuspendedAndLogout() {
        availableListener?.remove(); availableListener = null
        myJobsListener?.remove();    myJobsListener    = null
        auth.signOut()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_suspended_title))
            .setMessage(getString(R.string.dialog_suspended_msg))
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            .setCancelable(false)
            .show()
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
        availableJobsInitialized = false
        knownJobIds.clear()

        availableListener = db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("status", "open")
            .whereEqualTo("detectedCategory", category)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(this, "Failed to load jobs: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val allRequests = snapshot.documents.map { it.toVendorRequest() }
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
                val vLat = vendorLat
                val vLon = vendorLon

                val filtered = if (vLat != null && vLon != null) {
                    allRequests.mapNotNull { req ->
                        val rLat = req.lat
                        val rLon = req.lon
                        if (rLat == null || rLon == null) {
                            req
                        } else {
                            val dist = haversine(vLat, vLon, rLat, rLon)
                            if (dist <= Constants.MAX_DISTANCE_KM) req.copy(distanceKm = dist) else null
                        }
                    }
                } else {
                    allRequests
                }

                val filteredIds = filtered.map { it.id }.toSet()
                if (!availableJobsInitialized) {
                    availableJobsInitialized = true
                    knownJobIds.addAll(filteredIds)
                    newJobIds.clear()
                } else {
                    val addedIds = filteredIds - knownJobIds
                    knownJobIds.clear()
                    knownJobIds.addAll(filteredIds)
                    newJobIds.clear()
                    newJobIds.addAll(addedIds)
                    if (addedIds.isNotEmpty()) playNewJobSound()
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

    private fun playNewJobSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) { }
    }

    private fun startMyJobsListener() {
        myJobsInitialized = false
        knownMyJobIds.clear()
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

                val currentIds = myJobs.map { it.id }.toSet()
                if (!myJobsInitialized) {
                    myJobsInitialized = true
                    knownMyJobIds.addAll(currentIds)
                } else {
                    val newlyAccepted = myJobs.filter { it.id !in knownMyJobIds }
                    knownMyJobIds.clear()
                    knownMyJobIds.addAll(currentIds)
                    if (newlyAccepted.isNotEmpty()) {
                        playNewJobSound()
                        showBidAcceptedDialog(newlyAccepted.first())
                    }
                }

                myJobsAdapter.notifyDataSetChanged()
                binding.emptyMyJobsLayout.visibility =
                    if (myJobs.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun showBidAcceptedDialog(req: VendorRequest) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_bid_accepted_vendor_title))
            .setMessage(getString(R.string.dialog_bid_accepted_vendor_msg, req.title))
            .setPositiveButton(getString(R.string.dialog_view_my_jobs)) { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_myjobs
            }
            .setNegativeButton(getString(R.string.dialog_later), null)
            .show()
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
            .setTitle(getString(R.string.dialog_mark_complete_title))
            .setMessage(getString(R.string.dialog_mark_complete_msg, req.title))
            .setPositiveButton(getString(R.string.dialog_mark_complete_positive)) { _, _ -> doMarkComplete(req) }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
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
                    .setTitle(getString(R.string.dialog_job_complete_title))
                    .setMessage(getString(R.string.dialog_job_complete_msg))
                    .setPositiveButton(getString(R.string.dialog_ok), null)
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
            if (newJobIds.remove(req.id)) {
                holder.b.root.startAnimation(
                    AnimationUtils.loadAnimation(this@VendorDashboardActivity, R.anim.new_job_alert)
                )
            }
            with(holder.b) {
                tvCustomerName.text = "👤 ${req.customerName}"
                tvTitle.text        = req.title

                val label = req.detectedClass.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                tvDamage.text     = "$label · ${req.detectedCategory}"
                tvPriceRange.text = "💰 PKR ${String.format(Locale.US, "%,d", req.priceMin)}" +
                        " – ${String.format(Locale.US, "%,d", req.priceMax)}"

                if (req.acceptedBidAmount > 0 && (req.status == "assigned" || req.status == "completed")) {
                    tvAcceptedBid.text = getString(R.string.label_your_bid, String.format(Locale.US, "%,d", req.acceptedBidAmount))
                    tvAcceptedBid.visibility = View.VISIBLE
                } else {
                    tvAcceptedBid.visibility = View.GONE
                }

                val dist = req.distanceKm
                if (dist != null) {
                    tvDistance.text = getString(R.string.label_km_away, String.format("%.1f", dist))
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
                        tvStatus.text = when (req.status) {
                            "assigned"  -> getString(R.string.status_assigned)
                            "completed" -> getString(R.string.status_completed)
                            else        -> req.status.replaceFirstChar(Char::uppercase)
                        }
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

                val showChat = onChatClick != null && (isAssigned || req.status == "completed")
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
        acceptedBidAmount = getLong("acceptedBidAmount")?.toInt() ?: 0,
        status           = getString("status") ?: "",
        createdAt        = getTimestamp("createdAt"),
        lat              = getDouble("latitude").takeUnless { it == null || it == 0.0 },
        lon              = getDouble("longitude").takeUnless { it == null || it == 0.0 }
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
