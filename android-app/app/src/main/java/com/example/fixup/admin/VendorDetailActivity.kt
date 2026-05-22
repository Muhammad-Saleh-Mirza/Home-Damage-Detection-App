package com.example.fixup.admin

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.databinding.ActivityVendorDetailBinding
import com.example.fixup.databinding.ItemAdminRequestBinding
import com.example.fixup.utils.Constants
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class VendorDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVendorDetailBinding
    private val db = FirebaseFirestore.getInstance()

    private val bids = mutableListOf<BidItem>()
    private lateinit var bidsAdapter: BidsAdapter

    data class BidItem(
        val requestId: String,
        val amount: Long,
        val estimatedHours: Long,
        val note: String,
        val status: String,
        val createdAt: Timestamp?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVendorDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val vendorId   = intent.getStringExtra("vendorId")   ?: run { finish(); return }
        val vendorName = intent.getStringExtra("vendorName") ?: "Vendor"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = vendorName

        bidsAdapter = BidsAdapter(bids)
        binding.recyclerBids.layoutManager = LinearLayoutManager(this)
        binding.recyclerBids.adapter = bidsAdapter
        binding.recyclerBids.isNestedScrollingEnabled = false

        loadVendorProfile(vendorId)
        loadVendorBids(vendorId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    private fun loadVendorProfile(vendorId: String) {
        db.collection(Constants.COLLECTION_USERS).document(vendorId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val active = doc.getBoolean("isActive") ?: true

                binding.tvVendorName.text   = doc.getString("name") ?: ""
                binding.tvCategory.text     = doc.getString("serviceCategory") ?: ""
                binding.tvRating.text       = "★ ${String.format("%.1f", doc.getDouble("rating") ?: 0.0)}"
                binding.tvTotalJobs.text    = "${doc.getLong("totalJobs") ?: 0} jobs completed"
                binding.tvActiveStatus.text = if (active) "Active" else "Suspended"
                binding.tvActiveStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(if (active) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                }
            }
    }

    // ── Bids ───────────────────────────────────────────────────────────────────

    private fun loadVendorBids(vendorId: String) {
        db.collection(Constants.COLLECTION_BIDS)
            .whereEqualTo("vendorId", vendorId)
            .get()
            .addOnSuccessListener { snapshot ->
                bids.clear()
                for (doc in snapshot.documents) {
                    bids.add(
                        BidItem(
                            requestId      = doc.getString("requestId") ?: "",
                            amount         = doc.getLong("amount") ?: 0L,
                            estimatedHours = doc.getLong("estimatedHours") ?: 0L,
                            note           = doc.getString("note") ?: "",
                            status         = doc.getString("status") ?: "",
                            createdAt      = doc.getTimestamp("createdAt")
                        )
                    )
                }
                bids.sortByDescending { it.createdAt?.seconds ?: 0L }
                binding.progressBar.visibility = View.GONE
                binding.tvBidsHeader.text      = "Submitted Bids (${bids.size})"
                bidsAdapter.notifyDataSetChanged()
                binding.tvNoBids.visibility = if (bids.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                binding.progressBar.visibility = View.GONE
                binding.tvNoBids.visibility    = View.VISIBLE
            }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class BidsAdapter(
        private val items: List<BidItem>
    ) : RecyclerView.Adapter<BidsAdapter.VH>() {

        inner class VH(val b: ItemAdminRequestBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAdminRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bid = items[position]
            with(holder.b) {
                tvCustomerName.text = "Request ID: ${bid.requestId.take(8)}…"
                tvTitle.text        = "PKR ${String.format(Locale.US, "%,d", bid.amount)}"
                tvCategory.text     = "${bid.estimatedHours}h estimated" +
                        if (bid.note.isNotEmpty()) " · ${bid.note.take(60)}" else ""
                tvPriceRange.visibility = View.GONE

                tvDate.text = bid.createdAt?.toDate()?.let {
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(it)
                } ?: ""

                val (label, color) = when (bid.status) {
                    "pending"  -> "Pending"  to "#FF9800"
                    "accepted" -> "Accepted" to "#4CAF50"
                    "rejected" -> "Rejected" to "#F44336"
                    else       -> bid.status.replaceFirstChar(Char::uppercase) to "#607D8B"
                }
                tvStatus.text = label
                tvStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(Color.parseColor(color))
                }
            }
        }
    }
}
