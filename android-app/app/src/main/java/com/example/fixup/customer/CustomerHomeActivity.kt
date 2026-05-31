package com.example.fixup.customer

import android.content.Intent
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.R
import com.example.fixup.auth.LoginActivity
import com.example.fixup.databinding.ActivityCustomerHomeBinding
import com.example.fixup.databinding.ItemRequestBinding
import com.example.fixup.shared.ChatActivity
import com.example.fixup.shared.ChatbotActivity
import com.example.fixup.utils.Constants
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
        val createdAt: Timestamp?
    )

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
                    "completed" -> checkAndRate(req)
                    "assigned"  -> openChat(req)
                    else        -> openBids(req)
                }
            },
            onDeleteClick = { req, position -> confirmDeleteRequest(req, position) }
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
        val uid = auth.currentUser?.uid ?: return

        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: ""
                if (currentUserName.isNotEmpty()) {
                    supportActionBar?.subtitle = currentUserName
                    binding.tvWelcomeGreeting.text = "Welcome, $currentUserName!"
                }
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
                            createdAt           = doc.getTimestamp("createdAt")
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

    private fun checkAndRate(req: ServiceRequest) {
        startActivity(Intent(this, RateVendorActivity::class.java).apply {
            putExtra("requestId", req.id)
            putExtra("vendorId",  req.acceptedVendorId)
        })
    }

    private fun confirmDeleteRequest(req: ServiceRequest, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Request")
            .setMessage("Delete \"${req.title}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
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
            .setNegativeButton("Cancel", null)
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
        private val onDeleteClick: (ServiceRequest, Int) -> Unit
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

                tvStatus.text = req.status.replaceFirstChar(Char::uppercase)
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
                    tvAssignedTo.text = "👷 Assigned to: ${req.acceptedVendorName}"
                    tvAssignedTo.visibility = View.VISIBLE
                } else {
                    tvAssignedTo.visibility = View.GONE
                }

                if (req.acceptedBidAmount > 0 && req.status != "open") {
                    tvAcceptedAmount.text = "✅ Accepted Bid: PKR ${String.format(Locale.US, "%,d", req.acceptedBidAmount)}"
                    tvAcceptedAmount.visibility = View.VISIBLE
                } else {
                    tvAcceptedAmount.visibility = View.GONE
                }

                btnDelete.visibility = if (req.status == "open") View.VISIBLE else View.GONE
                btnDelete.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) onDeleteClick(req, pos)
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
                        "open"      -> "View Bids"   to "#4CAF50"
                        "assigned"  -> "Open Chat"   to "#1976D2"
                        "completed" -> "Rate Vendor"  to "#FF9800"
                        else        -> "View"         to "#4CAF50"
                    }
                    btnAction.text = btnText
                    btnAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor(btnColor))
                    btnAction.setOnClickListener { onActionClick(req) }
                }

                root.setOnClickListener { onCardClick(req) }
            }
        }
    }
}
