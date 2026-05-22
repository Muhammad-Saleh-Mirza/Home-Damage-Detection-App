package com.example.fixup.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.fixup.admin.AdminDashboardActivity
import com.example.fixup.customer.CustomerHomeActivity
import com.example.fixup.databinding.ActivityLoginBinding
import com.example.fixup.utils.Constants
import com.example.fixup.vendor.VendorDashboardActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.tvForgotPassword.setOnClickListener { showForgotPassword() }
    }

    private fun showForgotPassword() {
        val email = binding.etEmail.text.toString().trim()
        if (email.isEmpty()) {
            Toast.makeText(this, "Enter your email first", Toast.LENGTH_SHORT).show()
            return
        }
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "Reset email sent to $email", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun attemptLogin() {
        binding.tilEmail.error    = null
        binding.tilPassword.error = null

        val email    = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        var valid = true
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
        }
        if (!valid) return

        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                fetchUserAndRoute(uid)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                when (e) {
                    is FirebaseAuthInvalidUserException -> {
                        binding.tilEmail.error = "No account found with this email"
                    }
                    is FirebaseAuthInvalidCredentialsException -> {
                        binding.tilPassword.error = "Incorrect password. Please try again."
                    }
                    else -> {
                        binding.tilPassword.error = "Login failed. Please try again."
                    }
                }
            }
    }

    private fun fetchUserAndRoute(uid: String) {
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                setLoading(false)
                if (!doc.exists()) {
                    Toast.makeText(this, "User data not found.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val role = doc.getString("role") ?: ""
                val isActive = doc.getBoolean("isActive") ?: true
                routeByRole(role, isActive)
            }
            .addOnFailureListener {
                setLoading(false)
                Toast.makeText(this, "Failed to load user data.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun routeByRole(role: String, isActive: Boolean) {
        when (role) {
            Constants.ROLE_CUSTOMER -> {
                startActivity(Intent(this, CustomerHomeActivity::class.java))
                finishAffinity()
            }
            Constants.ROLE_VENDOR -> {
                if (isActive) {
                    startActivity(Intent(this, VendorDashboardActivity::class.java))
                    finishAffinity()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Account Suspended")
                        .setMessage("Your vendor account has been suspended. Please contact support.")
                        .setPositiveButton("OK", null)
                        .show()
                    auth.signOut()
                }
            }
            Constants.ROLE_ADMIN -> {
                startActivity(Intent(this, AdminDashboardActivity::class.java))
                finishAffinity()
            }
            else -> Toast.makeText(this, "Unknown account type.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
