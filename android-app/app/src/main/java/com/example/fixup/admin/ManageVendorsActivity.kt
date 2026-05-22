package com.example.fixup.admin

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.databinding.ActivityManageVendorsBinding
import com.example.fixup.databinding.ItemVendorBinding
import com.example.fixup.utils.Constants
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class ManageVendorsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageVendorsBinding
    private val db = FirebaseFirestore.getInstance()

    private val allVendors      = mutableListOf<Vendor>()
    private val filteredVendors = mutableListOf<Vendor>()
    private lateinit var adapter: VendorsAdapter
    private var vendorListener: ListenerRegistration? = null

    private var selectedCategory = ""   // "" = All

    data class Vendor(
        val id: String,
        val name: String,
        val serviceCategory: String,
        val rating: Double,
        val totalJobs: Int,
        var isActive: Boolean,
        val email: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageVendorsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = VendorsAdapter(
            items       = filteredVendors,
            onToggle    = { vendor, checked -> toggleActive(vendor, checked) },
            onCardClick = { vendor -> openVendorDetail(vendor) }
        )
        binding.recyclerVendors.layoutManager = LinearLayoutManager(this)
        binding.recyclerVendors.adapter = adapter

        setupChips()
    }

    override fun onStart() {
        super.onStart()
        startVendorListener()
    }

    override fun onStop() {
        super.onStop()
        vendorListener?.remove()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Chips ──────────────────────────────────────────────────────────────────

    private fun setupChips() {
        val chipCategoryMap = mapOf(
            binding.chipAll         to "",
            binding.chipMason       to "Plastering / Mason",
            binding.chipElectrician to "Electrician",
            binding.chipPainter     to "Painter",
            binding.chipPlumber     to "Plumber / Mason"
        )

        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedCategory = chipCategoryMap.entries
                .firstOrNull { it.key.id == chipId }?.value ?: ""
            applyFilter()
        }
    }

    // ── Firestore listener ─────────────────────────────────────────────────────

    private fun startVendorListener() {
        vendorListener?.remove()
        vendorListener = db.collection(Constants.COLLECTION_USERS)
            .whereEqualTo("role", Constants.ROLE_VENDOR)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                allVendors.clear()
                for (doc in snapshot.documents) {
                    allVendors.add(
                        Vendor(
                            id              = doc.id,
                            name            = doc.getString("name") ?: "",
                            serviceCategory = doc.getString("serviceCategory") ?: "",
                            rating          = doc.getDouble("rating") ?: 0.0,
                            totalJobs       = doc.getLong("totalJobs")?.toInt() ?: 0,
                            isActive        = doc.getBoolean("isActive") ?: true,
                            email           = doc.getString("email") ?: ""
                        )
                    )
                }
                allVendors.sortBy { it.name }
                binding.progressBar.visibility = View.GONE
                applyFilter()
            }
    }

    // ── Filter ─────────────────────────────────────────────────────────────────

    private fun applyFilter() {
        filteredVendors.clear()
        filteredVendors.addAll(
            if (selectedCategory.isEmpty()) allVendors
            else allVendors.filter { it.serviceCategory == selectedCategory }
        )
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (filteredVendors.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── Toggle active ──────────────────────────────────────────────────────────

    private fun toggleActive(vendor: Vendor, isActive: Boolean) {
        db.collection(Constants.COLLECTION_USERS).document(vendor.id)
            .update("isActive", isActive)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun openVendorDetail(vendor: Vendor) {
        startActivity(Intent(this, VendorDetailActivity::class.java).apply {
            putExtra("vendorId",   vendor.id)
            putExtra("vendorName", vendor.name)
        })
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class VendorsAdapter(
        private val items: List<Vendor>,
        private val onToggle: (Vendor, Boolean) -> Unit,
        private val onCardClick: (Vendor) -> Unit
    ) : RecyclerView.Adapter<VendorsAdapter.VH>() {

        inner class VH(val b: ItemVendorBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemVendorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vendor = items[position]
            with(holder.b) {
                tvVendorName.text = vendor.name
                tvCategory.text   = vendor.serviceCategory
                tvRating.text     = "★ ${String.format("%.1f", vendor.rating)}"
                tvTotalJobs.text  = "${vendor.totalJobs} jobs"

                val active = vendor.isActive
                tvActiveStatus.text = if (active) "Active" else "Suspended"
                tvActiveStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(if (active) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
                }

                // Detach listener before setting checked state to avoid loop
                switchActive.setOnCheckedChangeListener(null)
                switchActive.isChecked = active
                switchActive.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    vendor.isActive = checked
                    notifyItemChanged(position)
                    onToggle(vendor, checked)
                }

                root.setOnClickListener { onCardClick(vendor) }
            }
        }
    }
}
