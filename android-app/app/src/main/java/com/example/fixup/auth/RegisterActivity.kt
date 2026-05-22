package com.example.fixup.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fixup.customer.CustomerHomeActivity
import com.example.fixup.databinding.ActivityRegisterBinding
import com.example.fixup.utils.Constants
import com.example.fixup.vendor.VendorDashboardActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private var selectedRole    = Constants.ROLE_CUSTOMER
    private var pendingVendorUid: String? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val categoryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            serviceCategories
        )
        binding.actvCategory.setAdapter(categoryAdapter)

        setRoleCustomer()

        binding.cardCustomer.setOnClickListener { setRoleCustomer() }
        binding.cardVendor.setOnClickListener   { setRoleVendor() }
        binding.btnRegister.setOnClickListener  { attemptRegister() }
        binding.tvLogin.setOnClickListener      { finish() }
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
        // Clear previous errors
        binding.tilName.error     = null
        binding.tilEmail.error    = null
        binding.tilPassword.error = null
        binding.tilPhone.error    = null
        binding.tilCategory.error = null

        val name     = binding.etName.text.toString().trim()
        val email    = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val phone    = binding.etPhone.text.toString().trim()

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

        val isVendor         = selectedRole == Constants.ROLE_VENDOR
        val selectedCategory = binding.actvCategory.text.toString().trim()

        if (isVendor && selectedCategory.isEmpty()) {
            binding.tilCategory.error = "Please select a service category"
            valid = false
        }

        if (!valid) return

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener

                val userDoc = hashMapOf<String, Any>(
                    "name"      to name,
                    "email"     to email,
                    "phone"     to phone,
                    "role"      to selectedRole,
                    "city"      to Constants.DEFAULT_CITY,
                    "isActive"  to true,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                if (isVendor) {
                    userDoc["serviceCategory"] = selectedCategory
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
        binding.btnRegister.isEnabled      = !loading
        binding.progressBar.visibility     = if (loading) View.VISIBLE else View.GONE
    }
}
