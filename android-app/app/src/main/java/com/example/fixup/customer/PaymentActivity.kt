package com.example.fixup.customer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.fixup.databinding.ActivityPaymentBinding
import com.example.fixup.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding
    private val db   = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var requestId  = ""
    private var vendorId   = ""
    private var vendorName = ""
    private var amount     = 0

    private enum class Method { EASYPAISA, JAZZCASH, BANK, CASH }
    private var selected = Method.EASYPAISA

    private val colorSelected = Color.parseColor("#4CAF50")
    private val colorDefault  = Color.parseColor("#E0E0E0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Complete Payment"

        requestId  = intent.getStringExtra("requestId")  ?: run { finish(); return }
        vendorId   = intent.getStringExtra("vendorId")   ?: ""
        vendorName = intent.getStringExtra("vendorName") ?: "Vendor"
        amount     = intent.getIntExtra("amount", 0)

        val amountFormatted = "PKR ${String.format(Locale.US, "%,d", amount)}"
        binding.tvPaymentAmount.text   = amountFormatted
        binding.tvPayToVendor.text     = "to $vendorName"
        binding.btnConfirmPayment.text = "Pay $amountFormatted"

        setupBankDropdown()

        binding.cardEasypaisa.setOnClickListener { selectMethod(Method.EASYPAISA) }
        binding.cardJazzcash.setOnClickListener  { selectMethod(Method.JAZZCASH)  }
        binding.cardBank.setOnClickListener      { selectMethod(Method.BANK)      }
        binding.cardCash.setOnClickListener      { selectMethod(Method.CASH)      }

        // EasyPaisa selected by default
        selectMethod(Method.EASYPAISA)

        binding.btnConfirmPayment.setOnClickListener { startPayment() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun setupBankDropdown() {
        val banks = listOf(
            "Meezan Bank",
            "United Bank Limited (UBL)",
            "Habib Bank Limited (HBL)",
            "Bank Al-Falah",
            "Askari Bank"
        )
        binding.actvBank.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, banks)
        )
    }

    private fun selectMethod(method: Method) {
        selected = method

        binding.cardEasypaisa.strokeColor = if (method == Method.EASYPAISA) colorSelected else colorDefault
        binding.cardJazzcash.strokeColor  = if (method == Method.JAZZCASH)  colorSelected else colorDefault
        binding.cardBank.strokeColor      = if (method == Method.BANK)      colorSelected else colorDefault
        binding.cardCash.strokeColor      = if (method == Method.CASH)      colorSelected else colorDefault

        binding.tvCheckEasypaisa.visibility = if (method == Method.EASYPAISA) View.VISIBLE else View.INVISIBLE
        binding.tvCheckJazzcash.visibility  = if (method == Method.JAZZCASH)  View.VISIBLE else View.INVISIBLE
        binding.tvCheckBank.visibility      = if (method == Method.BANK)      View.VISIBLE else View.INVISIBLE
        binding.tvCheckCash.visibility      = if (method == Method.CASH)      View.VISIBLE else View.INVISIBLE

        binding.layoutAccountNumber.visibility     = if (method == Method.EASYPAISA || method == Method.JAZZCASH) View.VISIBLE else View.GONE
        binding.layoutBankSelect.visibility        = if (method == Method.BANK) View.VISIBLE else View.GONE
        binding.layoutBankAccountNumber.visibility = if (method == Method.BANK) View.VISIBLE else View.GONE

        // Update hint for EasyPaisa vs JazzCash
        if (method == Method.EASYPAISA) binding.layoutAccountNumber.hint = "EasyPaisa number (11 digits)"
        if (method == Method.JAZZCASH)  binding.layoutAccountNumber.hint = "JazzCash number (11 digits)"
    }

    private fun startPayment() {
        binding.btnConfirmPayment.isEnabled = false
        binding.layoutProcessing.visibility = View.VISIBLE

        // Phase 1 — spinner for 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            showSuccess()
            markAsPaidAndNotify()

            // Phase 2 — success shown for 1.5 seconds then navigate
            Handler(Looper.getMainLooper()).postDelayed({
                navigateToRate()
            }, 1500)
        }, 2000)
    }

    private fun showSuccess() {
        binding.progressPayment.visibility  = View.GONE
        binding.tvSuccessIcon.visibility    = View.VISIBLE
        binding.tvProcessingStatus.text     = "Payment Sent! 🎉"
        binding.tvProcessingSubtext.text    = "Redirecting to rate vendor…"
    }

    private fun markAsPaidAndNotify() {
        db.collection(Constants.COLLECTION_REQUESTS).document(requestId)
            .update("isPaid", true)

        val methodLabel = when (selected) {
            Method.EASYPAISA -> "EasyPaisa"
            Method.JAZZCASH  -> "JazzCash"
            Method.BANK      -> {
                val bank = binding.actvBank.text.toString().trim().ifEmpty { "Bank Transfer" }
                bank
            }
            Method.CASH      -> "Cash"
        }

        val uid = auth.currentUser?.uid ?: return
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                val senderName = doc.getString("name") ?: "Customer"
                val msg = hashMapOf(
                    "senderId"   to uid,
                    "senderName" to senderName,
                    "text"       to "✅ Payment of PKR ${String.format(Locale.US, "%,d", amount)} sent via $methodLabel. Thank you! 🙏",
                    "timestamp"  to FieldValue.serverTimestamp()
                )
                db.collection(Constants.COLLECTION_CHATS).document(requestId)
                    .collection("messages")
                    .add(msg)
            }
    }

    private fun navigateToRate() {
        startActivity(Intent(this, RateVendorActivity::class.java).apply {
            putExtra("requestId", requestId)
            putExtra("vendorId",  vendorId)
        })
        finish()
    }
}
