package com.example.fixup.customer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.media.RingtoneManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.fixup.R
import com.example.fixup.auth.LoginActivity
import com.example.fixup.databinding.ActivityCustomerHomeBinding
import com.example.fixup.databinding.ItemRequestBinding
import com.example.fixup.shared.ChatActivity
import com.example.fixup.shared.ChatbotActivity
import com.example.fixup.shared.TrackingActivity
import com.example.fixup.utils.Constants
import com.example.fixup.utils.LocaleHelper
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Locale

class CustomerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomerHomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private var currentUserName = ""
    private var cachedProfilePicUrl = ""
    private var toolbarAvatarView: ImageView? = null

    private val requests = mutableListOf<ServiceRequest>()
    private lateinit var adapter: RequestsAdapter
    private var snapshotListener: ListenerRegistration? = null

    private val ratedRequestIds = mutableSetOf<String>()
    private var ratingsListener: ListenerRegistration? = null

    data class ServiceRequest(
        val id: String,
        val title: String,
        val detectedCategory: String,
        val status: String,
        val acceptedVendorId: String,
        val acceptedVendorName: String,
        val acceptedBidAmount: Int,
        val createdAt: Timestamp?,
        val isVendorEnRoute: Boolean = false,
        val isPaid: Boolean = false
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        adapter = RequestsAdapter(
            items = requests,
            onCardClick = { req ->
                startActivity(Intent(this, RequestStatusActivity::class.java).apply {
                    putExtra("requestId", req.id)
                })
            },
            onActionClick = { req ->
                when (req.status) {
                    "completed" -> if (!req.isPaid) payNow(req) else checkAndRate(req)
                    "assigned"  -> openChat(req)
                    else        -> openBids(req)
                }
            },
            onDeleteClick = { req, position -> confirmDeleteRequest(req, position) },
            onChatClick   = { req -> openChat(req) }
        )
        binding.recyclerRequests.layoutManager = LinearLayoutManager(this)
        binding.recyclerRequests.adapter = adapter

        binding.btnPostRequest.setOnClickListener {
            startActivity(Intent(this, PostRequestActivity::class.java))
        }

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
        val uid = auth.currentUser?.uid ?: return

        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: ""
                if (currentUserName.isNotEmpty()) {
                    supportActionBar?.subtitle = currentUserName
                    binding.tvWelcomeGreeting.text = "Welcome, $currentUserName!"
                }
                loadAvatarImage(doc.getString("profilePictureUrl") ?: "")
            }

        snapshotListener = db.collection(Constants.COLLECTION_REQUESTS)
            .whereEqualTo("customerId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot == null) return@addSnapshotListener
                requests.clear()
                for (doc in snapshot.documents) {
                    requests.add(
                        ServiceRequest(
                            id                  = doc.id,
                            title               = doc.getString("title") ?: "",
                            detectedCategory    = doc.getString("detectedCategory") ?: "",
                            status              = doc.getString("status") ?: "open",
                            acceptedVendorId    = doc.getString("acceptedVendorId") ?: "",
                            acceptedVendorName  = doc.getString("acceptedVendorName") ?: "",
                            acceptedBidAmount   = doc.getLong("acceptedBidAmount")?.toInt() ?: 0,
                            createdAt           = doc.getTimestamp("createdAt"),
                            isVendorEnRoute     = doc.getBoolean("isVendorEnRoute") ?: false,
                            isPaid              = doc.getBoolean("isPaid") ?: false
                        )
                    )
                }
                requests.sortByDescending { it.createdAt?.seconds ?: 0L }
                adapter.notifyDataSetChanged()
                binding.layoutEmpty.visibility =
                    if (requests.isEmpty()) View.VISIBLE else View.GONE
                if (requests.isNotEmpty()) {
                    binding.tvRequestsHeader.text = requests.size.toString()
                    binding.tvRequestsHeader.visibility = View.VISIBLE
                } else {
                    binding.tvRequestsHeader.visibility = View.GONE
                }
            }

        ratingsListener = db.collection(Constants.COLLECTION_RATINGS)
            .whereEqualTo("customerId", uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                ratedRequestIds.clear()
                snapshot.documents.mapNotNullTo(ratedRequestIds) { it.getString("requestId") }
                adapter.notifyDataSetChanged()
            }
    }

    override fun onStop() {
        super.onStop()
        snapshotListener?.remove()
        ratingsListener?.remove()
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showTab(0)
                    true
                }
                R.id.nav_requests -> {
                    showTab(1)
                    true
                }
                R.id.nav_chatbot -> {
                    startActivity(Intent(this, ChatbotActivity::class.java))
                    false
                }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun showTab(index: Int) {
        binding.contentHome.visibility     = if (index == 0) View.VISIBLE else View.GONE
        binding.contentRequests.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    // ── Navigation helpers ─────────────────────────────────────────────────────

    private fun openChat(req: ServiceRequest) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("requestId",  req.id)
            putExtra("vendorId",   req.acceptedVendorId)
            putExtra("vendorName", req.acceptedVendorName.ifEmpty { "Vendor" })
        })
    }

    private fun openBids(req: ServiceRequest) {
        startActivity(Intent(this, BidsActivity::class.java).apply {
            putExtra("requestId",    req.id)
            putExtra("requestTitle", req.title)
        })
    }

    private fun payNow(req: ServiceRequest) {
        startActivity(Intent(this, PaymentActivity::class.java).apply {
            putExtra("requestId",  req.id)
            putExtra("vendorId",   req.acceptedVendorId)
            putExtra("vendorName", req.acceptedVendorName.ifEmpty { "Vendor" })
            putExtra("amount",     req.acceptedBidAmount)
        })
    }

    private fun checkAndRate(req: ServiceRequest) {
        startActivity(Intent(this, RateVendorActivity::class.java).apply {
            putExtra("requestId", req.id)
            putExtra("vendorId",  req.acceptedVendorId)
        })
    }

    @SuppressLint("MissingPermission")
    private fun openTracking(req: ServiceRequest) {
        fun launch(lat: Double, lon: Double) {
            startActivity(Intent(this, TrackingActivity::class.java).apply {
                putExtra("requestId",         req.id)
                putExtra("vendorId",          req.acceptedVendorId)
                putExtra("vendorName",        req.acceptedVendorName.ifEmpty { "Vendor" })
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

    private fun confirmDeleteRequest(req: ServiceRequest, position: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_request_title))
            .setMessage(getString(R.string.dialog_delete_request_msg, req.title))
            .setPositiveButton(getString(R.string.dialog_delete_positive)) { _, _ ->
                playDeleteSound()
                if (position < requests.size) {
                    requests.removeAt(position)
                    adapter.notifyItemRemoved(position)
                }
                db.collection(Constants.COLLECTION_REQUESTS).document(req.id)
                    .delete()
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun playDeleteSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) { }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class RequestsAdapter(
        private val items: List<ServiceRequest>,
        private val onCardClick: (ServiceRequest) -> Unit,
        private val onActionClick: (ServiceRequest) -> Unit,
        private val onDeleteClick: (ServiceRequest, Int) -> Unit,
        private val onChatClick: (ServiceRequest) -> Unit
    ) : RecyclerView.Adapter<RequestsAdapter.VH>() {

        inner class VH(val b: ItemRequestBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val req = items[position]
            with(holder.b) {
                tvTitle.text   = req.title
                tvService.text = req.detectedCategory

                tvStatus.text = when (req.status) {
                    "open"      -> getString(R.string.status_open)
                    "assigned"  -> getString(R.string.status_assigned)
                    "completed" -> getString(R.string.status_completed)
                    else        -> req.status.replaceFirstChar(Char::uppercase)
                }
                val badgeColor = when (req.status) {
                    "open"      -> Color.parseColor("#4CAF50")
                    "assigned"  -> Color.parseColor("#FF9800")
                    "completed" -> Color.parseColor("#9E9E9E")
                    else        -> Color.parseColor("#4CAF50")
                }
                tvStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(badgeColor)
                }

                tvDate.text = req.createdAt?.toDate()?.let {
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
                } ?: "Just now"

                if (req.acceptedVendorName.isNotEmpty() && req.status != "open") {
                    tvAssignedTo.text = getString(R.string.label_assigned_to, req.acceptedVendorName)
                    tvAssignedTo.visibility = View.VISIBLE
                } else {
                    tvAssignedTo.visibility = View.GONE
                }

                // Track Location button — visible when assigned and vendor is en route
                if (req.status == "assigned" && req.isVendorEnRoute) {
                    btnTrackLocation.visibility = View.VISIBLE
                    btnTrackLocation.setOnClickListener { openTracking(req) }
                } else {
                    btnTrackLocation.visibility = View.GONE
                }

                if (req.acceptedBidAmount > 0 && req.status != "open") {
                    tvAcceptedAmount.text = getString(R.string.label_accepted_bid, String.format(Locale.US, "%,d", req.acceptedBidAmount))
                    tvAcceptedAmount.visibility = View.VISIBLE
                } else {
                    tvAcceptedAmount.visibility = View.GONE
                }

                btnDelete.visibility = if (req.status == "open") View.VISIBLE else View.GONE
                btnDelete.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDeleteClick(req, pos)
                }

                val alreadyRated = req.status == "completed" && req.id in ratedRequestIds
                if (alreadyRated) {
                    btnAction.visibility = View.GONE
                    tvRated.visibility   = View.VISIBLE
                    tvRated.background   = GradientDrawable().apply {
                        shape        = GradientDrawable.RECTANGLE
                        cornerRadius = 8f
                        setColor(Color.parseColor("#FFF3E0"))
                        setStroke(2, Color.parseColor("#FF9800"))
                    }
                } else {
                    tvRated.visibility   = View.GONE
                    btnAction.visibility = View.VISIBLE
                    val (btnText, btnColor) = when (req.status) {
                        "open"      -> getString(R.string.btn_view_bids_action)   to "#4CAF50"
                        "assigned"  -> getString(R.string.btn_open_chat_action)   to "#1976D2"
                        "completed" -> if (!req.isPaid)
                            getString(R.string.btn_pay_now_action) to "#4CAF50"
                        else
                            getString(R.string.btn_rate_vendor_action) to "#FF9800"
                        else        -> getString(R.string.btn_view)               to "#4CAF50"
                    }
                    btnAction.text = btnText
                    btnAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor(btnColor))
                    btnAction.setOnClickListener { onActionClick(req) }
                }

                // Secondary chat button — keep chat accessible on completed jobs
                if (req.status == "completed" && req.acceptedVendorId.isNotEmpty()) {
                    btnChat.visibility = View.VISIBLE
                    btnChat.setOnClickListener { onChatClick(req) }
                } else {
                    btnChat.visibility = View.GONE
                }

                root.setOnClickListener { onCardClick(req) }
            }
        }
    }
}
