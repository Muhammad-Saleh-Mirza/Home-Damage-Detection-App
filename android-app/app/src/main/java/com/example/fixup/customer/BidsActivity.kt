package com.example.fixup.customer

import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.R
import com.example.fixup.databinding.ActivityBidsBinding
import com.example.fixup.databinding.ItemBidBinding
import com.example.fixup.shared.ChatActivity
import com.example.fixup.utils.Constants
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale

class BidsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBidsBinding
    private val db = FirebaseFirestore.getInstance()

    private lateinit var requestId: String
    private var requestStatus = "open"
    private var acceptedBidId = ""

    private val bids = mutableListOf<Bid>()
    private lateinit var adapter: BidsAdapter
    private var bidsListener: ListenerRegistration? = null
    private val knownBidIds = mutableSetOf<String>()
    private val newBidIds   = mutableSetOf<String>()
    private var bidsInitialized = false

    data class Bid(
        val id: String,
        val vendorId: String,
        val vendorName: String,
        val vendorRating: Double,
        val amount: Int,
        val estimatedHours: Double,
        val note: String,
        val status: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBidsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
        val requestTitle = intent.getStringExtra("requestTitle") ?: "Bids"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = requestTitle

        adapter = BidsAdapter()
        binding.recyclerBids.layoutManager = LinearLayoutManager(this)
        binding.recyclerBids.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        binding.progressBar.visibility = View.VISIBLE
        binding.cardPrice.visibility   = View.GONE

        db.collection(Constants.COLLECTION_REQUESTS).document(requestId).get()
            .addOnSuccessListener { doc ->
                requestStatus = doc.getString("status") ?: "open"
                acceptedBidId = doc.getString("acceptedBidId") ?: ""

                val fmt = "%,d"
                val min  = doc.getLong("estimatedPriceMin")?.toInt() ?: 0
                val max  = doc.getLong("estimatedPriceMax")?.toInt() ?: 0
                val rec  = doc.getLong("estimatedPriceRecommended")?.toInt() ?: 0
                val reason = doc.getString("priceJustification") ?: ""

                binding.tvPriceRange.text       = "💰 PKR ${String.format(Locale.US, fmt, min)} – ${String.format(Locale.US, fmt, max)}"
                binding.tvPriceRecommended.text = "Recommended: PKR ${String.format(Locale.US, fmt, rec)}"
                binding.tvPriceReason.text      = "\"$reason\""
                binding.cardPrice.visibility    = View.VISIBLE

                startBidsListener()
            }
            .addOnFailureListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to load request details", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onStop() {
        super.onStop()
        bidsListener?.remove()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Bids listener ─────────────────────────────────────────────────────────

    private fun startBidsListener() {
        bidsInitialized = false
        knownBidIds.clear()
        bidsListener = db.collection(Constants.COLLECTION_BIDS)
            .whereEqualTo("requestId", requestId)
            .addSnapshotListener { snapshot, error ->
                binding.progressBar.visibility = View.GONE
                if (error != null) {
                    Toast.makeText(this, "Failed to load bids: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                bids.clear()
                for (doc in snapshot.documents) {
                    if (doc.getString("status") == "rejected") continue
                    bids.add(
                        Bid(
                            id             = doc.id,
                            vendorId       = doc.getString("vendorId") ?: "",
                            vendorName     = doc.getString("vendorName") ?: "Vendor",
                            vendorRating   = doc.getDouble("vendorRating") ?: 0.0,
                            amount         = doc.getLong("amount")?.toInt() ?: 0,
                            estimatedHours = doc.getDouble("estimatedHours") ?: 1.0,
                            note           = doc.getString("note") ?: "",
                            status         = doc.getString("status") ?: "pending"
                        )
                    )
                }
                bids.sortBy { it.amount }

                val currentIds = bids.map { it.id }.toSet()
                if (!bidsInitialized) {
                    bidsInitialized = true
                    knownBidIds.addAll(currentIds)
                    newBidIds.clear()
                } else {
                    val addedIds = currentIds - knownBidIds
                    knownBidIds.clear()
                    knownBidIds.addAll(currentIds)
                    newBidIds.clear()
                    newBidIds.addAll(addedIds)
                    if (addedIds.isNotEmpty()) playNewBidSound()
                }

                adapter.notifyDataSetChanged()
                binding.layoutBidsEmpty.visibility = if (bids.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun playNewBidSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) { }
    }

    // ── Accept flow ───────────────────────────────────────────────────────────

    private fun confirmAccept(bid: Bid) {
        AlertDialog.Builder(this)
            .setTitle("Accept Bid?")
            .setMessage("Accept ${bid.vendorName}'s bid of PKR ${String.format(Locale.US, "%,d", bid.amount)}?")
            .setPositiveButton("Accept") { _, _ -> acceptBid(bid) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun acceptBid(bid: Bid) {
        binding.progressBar.visibility = View.VISIBLE

        val batch = db.batch()

        batch.update(
            db.collection(Constants.COLLECTION_REQUESTS).document(requestId),
            mapOf(
                "status"              to "assigned",
                "acceptedBidId"       to bid.id,
                "acceptedVendorId"    to bid.vendorId,
                "acceptedVendorName"  to bid.vendorName,
                "acceptedBidAmount"   to bid.amount
            )
        )
        batch.update(
            db.collection(Constants.COLLECTION_BIDS).document(bid.id),
            "status", "accepted"
        )
        bids.filter { it.id != bid.id }.forEach { other ->
            batch.update(
                db.collection(Constants.COLLECTION_BIDS).document(other.id),
                "status", "rejected"
            )
        }

        batch.commit()
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setTitle("Bid Accepted!")
                    .setMessage("You are now connected with ${bid.vendorName}. A chat has been opened so you can coordinate the repair.")
                    .setPositiveButton("Open Chat") { _, _ ->
                        startActivity(Intent(this, ChatActivity::class.java).apply {
                            putExtra("requestId",  requestId)
                            putExtra("vendorId",   bid.vendorId)
                            putExtra("vendorName", bid.vendorName)
                        })
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Failed to accept bid: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class BidsAdapter : RecyclerView.Adapter<BidsAdapter.VH>() {

        inner class VH(val b: ItemBidBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemBidBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = bids.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bid = bids[position]
            if (newBidIds.remove(bid.id)) {
                holder.b.root.startAnimation(
                    AnimationUtils.loadAnimation(this@BidsActivity, R.anim.new_job_alert)
                )
            }
            with(holder.b) {
                tvVendorName.text = bid.vendorName
                tvRating.text     = "⭐ ${String.format(Locale.US, "%.1f", bid.vendorRating)}"
                tvAmount.text     = "PKR ${String.format(Locale.US, "%,d", bid.amount)}"
                val h = bid.estimatedHours
                tvHours.text      = if (h % 1 == 0.0) "Est. ${h.toInt()}h" else "Est. ${h}h"
                tvNote.text       = bid.note.ifBlank { "No additional note" }

                if (requestStatus == "open") {
                    btnAccept.visibility = View.VISIBLE
                    btnAccept.setOnClickListener { confirmAccept(bid) }
                } else {
                    btnAccept.visibility = View.GONE
                }
            }
        }
    }
}
