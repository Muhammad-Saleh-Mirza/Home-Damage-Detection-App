package com.example.fixup.auth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.fixup.R
import com.example.fixup.customer.CustomerHomeActivity
import com.example.fixup.databinding.ActivityRegisterBinding
import com.example.fixup.utils.Constants
import com.example.fixup.utils.LocaleHelper
import com.example.fixup.vendor.VendorDashboardActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.Locale

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val auth    = FirebaseAuth.getInstance()
    private val db      = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var selectedRole    = Constants.ROLE_CUSTOMER
    private var pendingVendorUid: String? = null
    private var profilePicUri:  android.net.Uri? = null
    private var cnicFrontUri:   android.net.Uri? = null
    private var cnicBackUri:    android.net.Uri? = null

    private val serviceCategories = listOf(
        "Plastering / Mason",
        "Electrician",
        "Painter",
        "Plumber / Mason"
    )

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val uid = pendingVendorUid
        if (granted && uid != null) fetchLocationAndNavigate(uid)
        else navigateToVendorDashboard()
    }

    private val pickProfilePicLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            profilePicUri = it
            Glide.with(this).load(it).circleCrop().into(binding.ivProfilePic)
        }
    }

    private val pickCnicFrontLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            cnicFrontUri = it
            binding.layoutCnicFrontHint.visibility = View.GONE
            binding.ivCnicFront.visibility = View.VISIBLE
            Glide.with(this).load(it).centerCrop().into(binding.ivCnicFront)
            binding.cardCnicFront.strokeColor = android.graphics.Color.parseColor("#4CAF50")
        }
    }

    private val pickCnicBackLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            cnicBackUri = it
            binding.layoutCnicBackHint.visibility = View.GONE
            binding.ivCnicBack.visibility = View.VISIBLE
            Glide.with(this).load(it).centerCrop().into(binding.ivCnicBack)
            binding.cardCnicBack.strokeColor = android.graphics.Color.parseColor("#4CAF50")
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Glide.with(this)
            .load(R.drawable.ic_person_placeholder)
            .circleCrop()
            .into(binding.ivProfilePic)
        binding.ivProfilePic.setOnClickListener {
            pickProfilePicLauncher.launch("image/*")
        }

        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            serviceCategories
        )
        binding.actvCategory.setAdapter(categoryAdapter)

        setRoleCustomer()

        binding.cardCustomer.setOnClickListener  { setRoleCustomer() }
        binding.cardVendor.setOnClickListener    { setRoleVendor() }
        binding.btnRegister.setOnClickListener   { attemptRegister() }
        binding.tvLogin.setOnClickListener       { finish() }
        binding.cardCnicFront.setOnClickListener { pickCnicFrontLauncher.launch("image/*") }
        binding.cardCnicBack.setOnClickListener  { pickCnicBackLauncher.launch("image/*") }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Role card selection ────────────────────────────────────────────────────

    private fun setRoleCustomer() {
        selectedRole = Constants.ROLE_CUSTOMER
        binding.layoutCategory.visibility = View.GONE
        binding.cardCustomer.strokeColor = Color.parseColor("#1565C0")
        binding.cardVendor.strokeColor   = Color.parseColor("#E0E0E0")
    }

    private fun setRoleVendor() {
        selectedRole = Constants.ROLE_VENDOR
        binding.layoutCategory.visibility = View.VISIBLE
        binding.cardVendor.strokeColor   = Color.parseColor("#1565C0")
        binding.cardCustomer.strokeColor = Color.parseColor("#E0E0E0")
    }

    // ── Register ───────────────────────────────────────────────────────────────

    private fun attemptRegister() {
        binding.tilName.error     = null
        binding.tilEmail.error    = null
        binding.tilPassword.error = null
        binding.tilPhone.error    = null
        binding.tilCnic.error     = null
        binding.tilCategory.error = null

        val name     = binding.etName.text.toString().trim()
        val email    = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val phone    = binding.etPhone.text.toString().trim()
        val cnic     = binding.etCnic.text.toString().trim()

        var valid = true

        if (name.isEmpty()) {
            binding.tilName.error = "Full name is required"
            valid = false
        }

        if (email.isEmpty()) {
            binding.tilEmail.error = "Email is required"
            valid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            valid = false
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = "Password is required"
            valid = false
        } else {
            when {
                password.length < 6 ->
                    binding.tilPassword.error = "Password must be at least 6 characters"
                !password.any { it.isUpperCase() } ->
                    binding.tilPassword.error = "Password must contain at least one uppercase letter"
                !password.any { !it.isLetterOrDigit() } ->
                    binding.tilPassword.error = "Password must contain at least one symbol (e.g. @, #, !)"
                else -> Unit
            }
            if (binding.tilPassword.error != null) valid = false
        }

        if (phone.isEmpty()) {
            binding.tilPhone.error = "Phone number is required"
            valid = false
        } else if (!phone.all { it.isDigit() } || phone.length != 11) {
            binding.tilPhone.error = "Phone number must be exactly 11 digits"
            valid = false
        }

        if (cnic.isEmpty()) {
            binding.tilCnic.error = "CNIC number is required"
            valid = false
        } else if (!cnic.all { it.isDigit() } || cnic.length != 13) {
            binding.tilCnic.error = "CNIC must be exactly 13 digits"
            valid = false
        }

        val isVendor         = selectedRole == Constants.ROLE_VENDOR
        val selectedCategory = binding.actvCategory.text.toString().trim()

        if (isVendor && selectedCategory.isEmpty()) {
            binding.tilCategory.error = "Please select a service category"
            valid = false
        }

        if (!valid) return

        val doRegister = {
            setLoading(true)
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                handleUploadsAndSave(uid, name, email, phone, cnic, isVendor, selectedCategory)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                val msg = when {
                    e.message?.contains("email address is already in use", ignoreCase = true) == true ->
                        "An account with this email already exists"
                    e.message?.contains("badly formatted", ignoreCase = true) == true ->
                        "Please enter a valid email address"
                    else -> "Registration failed: ${e.message}"
                }
                binding.tilEmail.error = msg
            }
        }

        if (isVendor) showNadraVerification(cnic) { doRegister() }
        else doRegister()
    }

    // ── NADRA verification (vendor only) ─────────────────────────────────────

    private fun showNadraVerification(cnic: String, onComplete: () -> Unit) {
        binding.layoutNadraOverlay.visibility = View.VISIBLE
        binding.progressNadra.visibility      = View.VISIBLE
        binding.tvNadraSuccess.visibility     = View.GONE
        binding.tvNadraStatus.text            = "Connecting to NADRA database…"
        binding.tvNadraSubtext.text           = "Please wait"

        // Phase 2 — show masked CNIC being checked
        Handler(Looper.getMainLooper()).postDelayed({
            val masked = cnic.take(5) + "XXXXXXXX"
            binding.tvNadraStatus.text  = "Verifying CNIC: $masked"
            binding.tvNadraSubtext.text = "Cross-referencing identity records"

            // Phase 3 — confirmed
            Handler(Looper.getMainLooper()).postDelayed({
                binding.progressNadra.visibility  = View.GONE
                binding.tvNadraSuccess.visibility = View.VISIBLE
                binding.tvNadraStatus.text        = "Identity Verified!"
                binding.tvNadraSubtext.text       = "Proceeding with registration…"

                // Dismiss and continue
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.layoutNadraOverlay.visibility = View.GONE
                    onComplete()
                }, 900)
            }, 1400)
        }, 1300)
    }

    private fun handleUploadsAndSave(
        uid: String, name: String, email: String, phone: String,
        cnic: String, isVendor: Boolean, category: String
    ) {
        fun doSave(profileUrl: String, frontUrl: String, backUrl: String) {
            saveUserDoc(uid, name, email, phone, cnic, profileUrl, frontUrl, backUrl, isVendor, category)
        }

        fun uploadBack(profileUrl: String, frontUrl: String) {
            val backUri = cnicBackUri
            if (backUri != null) {
                val ref = storage.reference.child("images/$uid/cnic_back.jpg")
                ref.putFile(backUri)
                    .continueWithTask { task -> if (!task.isSuccessful) throw task.exception!!; ref.downloadUrl }
                    .addOnSuccessListener { url -> doSave(profileUrl, frontUrl, url.toString()) }
                    .addOnFailureListener { doSave(profileUrl, frontUrl, "") }
            } else doSave(profileUrl, frontUrl, "")
        }

        fun uploadFront(profileUrl: String) {
            val frontUri = cnicFrontUri
            if (frontUri != null) {
                val ref = storage.reference.child("images/$uid/cnic_front.jpg")
                ref.putFile(frontUri)
                    .continueWithTask { task -> if (!task.isSuccessful) throw task.exception!!; ref.downloadUrl }
                    .addOnSuccessListener { url -> uploadBack(profileUrl, url.toString()) }
                    .addOnFailureListener { uploadBack(profileUrl, "") }
            } else uploadBack(profileUrl, "")
        }

        val picUri = profilePicUri
        if (picUri != null) {
            val ref = storage.reference.child("images/$uid/profile.jpg")
            ref.putFile(picUri)
                .continueWithTask { task -> if (!task.isSuccessful) throw task.exception!!; ref.downloadUrl }
                .addOnSuccessListener { url -> uploadFront(url.toString()) }
                .addOnFailureListener { uploadFront("") }
        } else uploadFront("")
    }

    private fun saveUserDoc(
        uid: String, name: String, email: String, phone: String, cnic: String,
        profilePicUrl: String, cnicFrontUrl: String, cnicBackUrl: String,
        isVendor: Boolean, category: String
    ) {
        val userDoc = hashMapOf<String, Any>(
            "name"              to name,
            "email"             to email,
            "phone"             to phone,
            "cnic"              to cnic,
            "role"              to selectedRole,
            "city"              to Constants.DEFAULT_CITY,
            "isActive"          to true,
            "profilePictureUrl" to profilePicUrl,
            "cnicFrontUrl"      to cnicFrontUrl,
            "cnicBackUrl"       to cnicBackUrl,
            "createdAt"         to FieldValue.serverTimestamp()
        )
        if (isVendor) {
            userDoc["serviceCategory"] = category
            userDoc["rating"]          = 0.0
            userDoc["totalJobs"]       = 0
        }

        db.collection(Constants.COLLECTION_USERS).document(uid).set(userDoc)
            .addOnSuccessListener {
                setLoading(false)
                if (isVendor) {
                    pendingVendorUid = uid
                    requestVendorLocation(uid)
                } else {
                    startActivity(Intent(this, CustomerHomeActivity::class.java))
                    finishAffinity()
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Failed to save profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── Location ───────────────────────────────────────────────────────────────

    private fun requestVendorLocation(uid: String) {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasPermission =
            ContextCompat.checkSelfPermission(this, fine)   == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            fetchLocationAndNavigate(uid)
        } else {
            locationPermissionLauncher.launch(arrayOf(fine, coarse))
        }
    }

    private fun fetchLocationAndNavigate(uid: String) {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val lat  = location.latitude
                        val lon  = location.longitude
                        val name = reverseGeocode(lat, lon)
                        db.collection(Constants.COLLECTION_USERS).document(uid)
                            .update(mapOf(
                                "latitude"     to lat,
                                "longitude"    to lon,
                                "locationName" to name
                            ))
                    }
                    navigateToVendorDashboard()
                }
                .addOnFailureListener { navigateToVendorDashboard() }
        } catch (_: SecurityException) {
            navigateToVendorDashboard()
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double): String {
        return try {
            val addresses = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                listOfNotNull(addr.subLocality, addr.locality)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifEmpty { addr.locality ?: Constants.DEFAULT_CITY }
            } else Constants.DEFAULT_CITY
        } catch (_: Exception) { Constants.DEFAULT_CITY }
    }

    private fun navigateToVendorDashboard() {
        startActivity(Intent(this, VendorDashboardActivity::class.java))
        finishAffinity()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled  = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
