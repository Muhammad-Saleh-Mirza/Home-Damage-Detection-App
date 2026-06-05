package com.example.fixup.admin

import android.content.Intent
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
import com.example.fixup.customer.RequestStatusActivity
import com.example.fixup.databinding.ActivityAllRequestsBinding
import com.example.fixup.databinding.ItemAdminRequestBinding
import com.example.fixup.utils.Constants
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Locale

class AllRequestsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllRequestsBinding
    private val db = FirebaseFirestore.getInstance()

    private val allRequests      = mutableListOf<AdminRequest>()
    private val filteredRequests = mutableListOf<AdminRequest>()
    private lateinit var adapter: RequestsAdapter
    private var requestListener: ListenerRegistration? = null

    private var selectedTab      = 0   // 0=All 1=Open 2=Assigned 3=Completed
    private var selectedCategory = ""  // "" = All

    data class AdminRequest(
        val id: String,
        val customerName: String,
        val title: String,
        val detectedCategory: String,
        val status: String,
        val priceMin: Long,
        val priceMax: Long,
        val createdAt: Timestamp?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = RequestsAdapter(filteredRequests)
        binding.recyclerRequests.layoutManager = LinearLayoutManager(this)
        binding.recyclerRequests.adapter = adapter

        setupTabs()
        setupChips()
    }

    override fun onStart() {
        super.onStart()
        startRequestListener()
    }

    override fun onStop() {
        super.onStop()
        requestListener?.remove()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Tabs ───────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        listOf("All", "Open", "Assigned", "Completed").forEach {
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(it))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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

    private fun startRequestListener() {
        requestListener?.remove()
        requestListener = db.collection(Constants.COLLECTION_REQUESTS)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                allRequests.clear()
                for (doc in snapshot.documents) {
                    allRequests.add(
                        AdminRequest(
                            id               = doc.id,
                            customerName     = doc.getString("customerName") ?: "",
                            title            = doc.getString("title") ?: "",
                            detectedCategory = doc.getString("detectedCategory") ?: "",
                            status           = doc.getString("status") ?: "",
                            priceMin         = doc.getLong("estimatedPriceMin") ?: 0L,
                            priceMax         = doc.getLong("estimatedPriceMax") ?: 0L,
                            createdAt        = doc.getTimestamp("createdAt")
                        )
                    )
                }
                allRequests.sortByDescending { it.createdAt?.seconds ?: 0L }
                binding.progressBar.visibility = View.GONE
                applyFilter()
            }
    }

    // ── Filter ─────────────────────────────────────────────────────────────────

    private fun applyFilter() {
        val statusFilter = when (selectedTab) {
            1    -> "open"
            2    -> "assigned"
            3    -> "completed"
            else -> ""
        }

        filteredRequests.clear()
        filteredRequests.addAll(
            allRequests.filter { req ->
                (statusFilter.isEmpty() || req.status == statusFilter) &&
                (selectedCategory.isEmpty() || req.detectedCategory == selectedCategory)
            }
        )
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (filteredRequests.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class RequestsAdapter(
        private val items: List<AdminRequest>
    ) : RecyclerView.Adapter<RequestsAdapter.VH>() {

        inner class VH(val b: ItemAdminRequestBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAdminRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val req = items[position]
            with(holder.b) {
                tvCustomerName.text = "👤 ${req.customerName}"
                tvTitle.text        = req.title
                tvCategory.text     = req.detectedCategory
                tvPriceRange.text   = "💰 PKR ${String.format(Locale.US, "%,d", req.priceMin)}" +
                        " – ${String.format(Locale.US, "%,d", req.priceMax)}"
                tvDate.text         = req.createdAt?.toDate()?.let {
                    SimpleDateFormat("MMM d", Locale.getDefault()).format(it)
                } ?: ""

                val (label, color) = when (req.status) {
                    "open"      -> "Open"      to "#4CAF50"
                    "assigned"  -> "Assigned"  to "#FF9800"
                    "completed" -> "Completed" to "#9E9E9E"
                    else        -> req.status.replaceFirstChar(Char::uppercase) to "#607D8B"
                }
                tvStatus.text = label
                tvStatus.background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(Color.parseColor(color))
                }

                root.setOnClickListener {
                    startActivity(Intent(this@AllRequestsActivity, RequestStatusActivity::class.java).apply {
                        putExtra("requestId", req.id)
                    })
                }
            }
        }
    }
}
