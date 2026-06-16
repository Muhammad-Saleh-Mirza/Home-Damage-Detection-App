package com.example.fixup.shared

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.fixup.R
import com.example.fixup.customer.RateVendorActivity
import com.example.fixup.databinding.ActivityChatBinding
import com.example.fixup.databinding.ItemMessageReceivedBinding
import com.example.fixup.databinding.ItemMessageSentBinding
import com.example.fixup.utils.Constants
import com.example.fixup.utils.LocaleHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private val uid get() = auth.currentUser?.uid ?: ""

    private lateinit var requestId: String
    private lateinit var vendorId: String
    private var isVendor = false
    private var currentUserName = ""
    private var requestTitle    = "request"

    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessagesAdapter
    private var messagesListener: ListenerRegistration? = null
    private var requestListener:  ListenerRegistration? = null

    private val profilePicCache = mutableMapOf<String, String>()

    // ── Tracking state ─────────────────────────────────────────────────────────
    private var currentStatus   = ""
    private var isVendorEnRoute = false
    private var fusedLocation: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVendorLocationUpdates()
        else Toast.makeText(this, "Location permission required to share position", Toast.LENGTH_LONG).show()
    }

    data class Message(
        val id: String,
        val senderId: String,
        val senderName: String,
        val text: String,
        val timestamp: Timestamp?
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId = intent.getStringExtra("requestId") ?: run { finish(); return }
        vendorId  = intent.getStringExtra("vendorId")  ?: ""
        val vendorName = intent.getStringExtra("vendorName") ?: "Vendor"

        isVendor = uid == vendorId

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (!isVendor) vendorName else "Chat"

        // All action buttons start hidden; updateButtons() controls visibility
        binding.btnStartJourney.visibility = View.GONE
        binding.btnMarkComplete.visibility = View.GONE
        binding.btnTrackVendor.visibility  = View.GONE
        binding.btnRateVendor.visibility   = View.GONE

        binding.btnStartJourney.setOnClickListener { onStartJourneyClicked() }
        binding.btnMarkComplete.setOnClickListener { confirmMarkComplete() }
        binding.btnTrackVendor.setOnClickListener  { openTrackingActivity() }
        binding.btnRateVendor.setOnClickListener {
            startActivity(Intent(this, RateVendorActivity::class.java).apply {
                putExtra("requestId", requestId)
                putExtra("vendorId",  vendorId)
            })
        }
        binding.btnSend.setOnClickListener { sendMessage() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, maxOf(sys.bottom, ime.bottom))
            insets
        }

        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.layoutManager = lm
        adapter = MessagesAdapter()
        binding.recyclerMessages.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        loadCurrentUserName()
        startRequestListener()
        startMessagesListener()
    }

    override fun onStop() {
        super.onStop()
        messagesListener?.remove()
        requestListener?.remove()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    private fun loadCurrentUserName() {
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: "User"
                profilePicCache[uid] = doc.getString("profilePictureUrl") ?: ""
                adapter.notifyDataSetChanged()
            }
        if (!isVendor && vendorId.isNotEmpty()) {
            db.collection(Constants.COLLECTION_USERS).document(vendorId).get()
                .addOnSuccessListener { doc ->
                    profilePicCache[vendorId] = doc.getString("profilePictureUrl") ?: ""
                    adapter.notifyDataSetChanged()
                }
        }
    }

    // ── Request listener ───────────────────────────────────────────────────────

    private fun startRequestListener() {
        requestListener = db.collection(Constants.COLLECTION_REQUESTS)
            .document(requestId)
            .addSnapshotListener { doc, _ ->
                if (doc == null || !doc.exists()) return@addSnapshotListener
                currentStatus   = doc.getString("status") ?: "assigned"
                isVendorEnRoute = doc.getBoolean("isVendorEnRoute") ?: false
                if (requestTitle == "request") requestTitle = doc.getString("title") ?: "request"

                if (isVendor) {
                    val customerName = doc.getString("customerName") ?: "Customer"
                    supportActionBar?.title = customerName
                    val customerId = doc.getString("customerId") ?: ""
                    if (customerId.isNotEmpty() && !profilePicCache.containsKey(customerId)) {
                        db.collection(Constants.COLLECTION_USERS).document(customerId).get()
                            .addOnSuccessListener { userDoc ->
                                profilePicCache[customerId] = userDoc.getString("profilePictureUrl") ?: ""
                                adapter.notifyDataSetChanged()
                            }
                    }
                }
                updateButtons()
            }
    }

    private fun updateButtons() {
        if (isVendor) {
            binding.btnTrackVendor.visibility = View.GONE
            binding.btnRateVendor.visibility  = View.GONE
            when {
                currentStatus == "assigned" && !isVendorEnRoute -> {
                    binding.btnStartJourney.visibility = View.VISIBLE
                    binding.btnMarkComplete.visibility = View.GONE
                }
                currentStatus == "assigned" && isVendorEnRoute -> {
                    binding.btnStartJourney.visibility = View.GONE
                    binding.btnMarkComplete.visibility = View.VISIBLE
                }
                else -> {
                    binding.btnStartJourney.visibility = View.GONE
                    binding.btnMarkComplete.visibility = View.GONE
                }
            }
        } else {
            binding.btnStartJourney.visibility = View.GONE
            binding.btnMarkComplete.visibility = View.GONE
            when {
                currentStatus == "assigned" && isVendorEnRoute -> {
                    binding.btnTrackVendor.visibility = View.VISIBLE
                    binding.btnRateVendor.visibility  = View.GONE
                }
                currentStatus == "completed" -> {
                    binding.btnTrackVendor.visibility = View.GONE
                    checkRatingAndShowButton()
                }
                else -> {
                    binding.btnTrackVendor.visibility = View.GONE
                    binding.btnRateVendor.visibility  = View.GONE
                }
            }
        }
    }

    // ── Messages ───────────────────────────────────────────────────────────────

    private fun startMessagesListener() {
        messagesListener = db.collection(Constants.COLLECTION_CHATS)
            .document(requestId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                messages.clear()
                for (doc in snapshot.documents) {
                    messages.add(
                        Message(
                            id         = doc.id,
                            senderId   = doc.getString("senderId") ?: "",
                            senderName = doc.getString("senderName") ?: "",
                            text       = doc.getString("text") ?: "",
                            timestamp  = doc.getTimestamp("timestamp")
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    binding.recyclerMessages.post {
                        binding.recyclerMessages.scrollToPosition(messages.size - 1)
                    }
                }
            }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.etMessage.setText("")
        db.collection(Constants.COLLECTION_CHATS)
            .document(requestId)
            .collection("messages")
            .add(
                hashMapOf(
                    "senderId"   to uid,
                    "senderName" to currentUserName.ifEmpty { "User" },
                    "text"       to text,
                    "timestamp"  to FieldValue.serverTimestamp()
                )
            )
    }

    // ── Vendor: start journey ──────────────────────────────────────────────────

    private fun onStartJourneyClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startVendorLocationUpdates()
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVendorLocationUpdates() {
        if (fusedLocation == null) fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        db.collection(Constants.COLLECTION_REQUESTS).document(requestId)
            .update("isVendorEnRoute", true)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                db.collection(Constants.COLLECTION_REQUESTS).document(requestId)
                    .update(
                        "vendorLatitude",  loc.latitude,
                        "vendorLongitude", loc.longitude
                    )
            }
        }
        fusedLocation?.requestLocationUpdates(request, locationCallback!!, mainLooper)
        Toast.makeText(this, "Journey started — customer can now track you", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocation?.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ── Vendor: mark job complete ──────────────────────────────────────────────

    private fun confirmMarkComplete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_mark_complete_title))
            .setMessage(getString(R.string.dialog_mark_complete_msg, requestTitle))
            .setPositiveButton(getString(R.string.dialog_mark_complete_positive)) { _, _ ->
                stopLocationUpdates()
                db.collection(Constants.COLLECTION_REQUESTS).document(requestId)
                    .update(
                        "status",          "completed",
                        "isVendorEnRoute", false,
                        "completedAt",     FieldValue.serverTimestamp()
                    )
                    .addOnSuccessListener {
                        db.collection(Constants.COLLECTION_USERS).document(vendorId)
                            .update("totalJobs", FieldValue.increment(1))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_job_complete_title))
                            .setMessage(getString(R.string.dialog_job_complete_msg))
                            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ── Customer: open tracking ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun openTrackingActivity() {
        val displayName = intent.getStringExtra("vendorName") ?: "Vendor"
        fun launch(lat: Double, lon: Double) {
            startActivity(Intent(this, TrackingActivity::class.java).apply {
                putExtra("requestId",         requestId)
                putExtra("vendorId",          vendorId)
                putExtra("vendorName",        displayName)
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

    // ── Rating check ──────────────────────────────────────────────────────────

    private fun checkRatingAndShowButton() {
        db.collection(Constants.COLLECTION_RATINGS)
            .whereEqualTo("requestId",  requestId)
            .whereEqualTo("customerId", uid)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                binding.btnRateVendor.visibility =
                    if (snapshot.isEmpty) View.VISIBLE else View.GONE
            }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val SENT     = 0
        private val RECEIVED = 1

        inner class SentVH(val b: ItemMessageSentBinding) : RecyclerView.ViewHolder(b.root)
        inner class ReceivedVH(val b: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) =
            if (messages[position].senderId == uid) SENT else RECEIVED

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == SENT)
                SentVH(ItemMessageSentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else
                ReceivedVH(ItemMessageReceivedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = messages.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg  = messages[position]
            val time = msg.timestamp?.toDate()?.let {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(it)
            } ?: ""

            when (holder) {
                is SentVH -> {
                    holder.b.tvMessageText.text = msg.text
                    holder.b.tvTimestamp.text   = time
                    val picUrl = profilePicCache[uid] ?: ""
                    if (picUrl.isNotEmpty()) {
                        Glide.with(this@ChatActivity)
                            .load(picUrl)
                            .circleCrop()
                            .into(holder.b.ivSenderAvatar)
                    }
                }
                is ReceivedVH -> {
                    holder.b.tvSenderName.text  = msg.senderName
                    holder.b.tvMessageText.text = msg.text
                    holder.b.tvTimestamp.text   = time
                    val picUrl = profilePicCache[msg.senderId] ?: ""
                    if (picUrl.isNotEmpty()) {
                        Glide.with(this@ChatActivity)
                            .load(picUrl)
                            .circleCrop()
                            .into(holder.b.ivSenderAvatar)
                    }
                }
            }
        }
    }
}
