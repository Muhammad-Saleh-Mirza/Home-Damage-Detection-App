package com.example.fixup.shared

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fixup.customer.RateVendorActivity
import com.example.fixup.databinding.ActivityChatBinding
import com.example.fixup.databinding.ItemMessageReceivedBinding
import com.example.fixup.databinding.ItemMessageSentBinding
import com.example.fixup.utils.Constants
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

    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessagesAdapter
    private var messagesListener: ListenerRegistration? = null
    private var requestListener: ListenerRegistration? = null

    data class Message(
        val id: String,
        val senderId: String,
        val senderName: String,
        val text: String,
        val timestamp: Timestamp?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestId  = intent.getStringExtra("requestId")  ?: run { finish(); return }
        vendorId   = intent.getStringExtra("vendorId")   ?: ""
        val vendorName = intent.getStringExtra("vendorName") ?: "Vendor"

        isVendor = uid == vendorId

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Customer sees vendor name immediately; vendor toolbar title set by request listener
        supportActionBar?.title = if (!isVendor) vendorName else "Chat"

        binding.btnMarkComplete.visibility = View.GONE
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── User profile ───────────────────────────────────────────────────────────

    private fun loadCurrentUserName() {
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc -> currentUserName = doc.getString("name") ?: "User" }
    }

    // ── Request listener — tracks status + customerName for vendor toolbar ─────

    private fun startRequestListener() {
        requestListener = db.collection(Constants.COLLECTION_REQUESTS)
            .document(requestId)
            .addSnapshotListener { doc, _ ->
                if (doc == null || !doc.exists()) return@addSnapshotListener
                val status = doc.getString("status") ?: "assigned"

                if (isVendor) {
                    val customerName = doc.getString("customerName") ?: "Customer"
                    supportActionBar?.title = customerName
                    binding.btnRateVendor.visibility = View.GONE
                } else {
                    binding.btnMarkComplete.visibility = View.GONE
                    if (status == "completed") {
                        checkRatingAndShowButton()
                    } else {
                        binding.btnRateVendor.visibility = View.GONE
                    }
                }
            }
    }

    // ── Messages listener ──────────────────────────────────────────────────────

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

    // ── Send message ───────────────────────────────────────────────────────────

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

    // ── Mark job complete (vendor) ─────────────────────────────────────────────

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
            val msg = messages[position]
            val time = msg.timestamp?.toDate()?.let {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(it)
            } ?: ""

            when (holder) {
                is SentVH -> {
                    holder.b.tvMessageText.text = msg.text
                    holder.b.tvTimestamp.text   = time
                }
                is ReceivedVH -> {
                    holder.b.tvSenderName.text  = msg.senderName
                    holder.b.tvMessageText.text = msg.text
                    holder.b.tvTimestamp.text   = time
                }
            }
        }
    }
}
