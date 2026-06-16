package com.example.fixup.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.fixup.admin.AdminDashboardActivity
import com.example.fixup.customer.CustomerHomeActivity
import com.example.fixup.databinding.ActivitySplashBinding
import com.example.fixup.utils.Constants
import com.example.fixup.utils.LocaleHelper
import com.example.fixup.vendor.VendorDashboardActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = auth.currentUser
        if (user == null) {
            goToLogin()
        } else {
            fetchUserAndRoute(user.uid)
        }
    }

    private fun fetchUserAndRoute(uid: String) {
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    auth.signOut()
                    goToLogin()
                    return@addOnSuccessListener
                }
                val role = doc.getString("role") ?: ""
                val isActive = doc.getBoolean("isActive") ?: true
                routeByRole(role, isActive)
            }
            .addOnFailureListener {
                auth.signOut()
                goToLogin()
            }
    }

    private fun routeByRole(role: String, isActive: Boolean) {
        when (role) {
            Constants.ROLE_CUSTOMER -> {
                startActivity(Intent(this, CustomerHomeActivity::class.java))
                finish()
            }
            Constants.ROLE_VENDOR -> {
                if (isActive) {
                    startActivity(Intent(this, VendorDashboardActivity::class.java))
                    finish()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Account Suspended")
                        .setMessage("Your vendor account has been suspended. Please contact support.")
                        .setPositiveButton("OK") { _, _ ->
                            auth.signOut()
                            goToLogin()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            Constants.ROLE_ADMIN -> {
                startActivity(Intent(this, AdminDashboardActivity::class.java))
                finish()
            }
            else -> {
                auth.signOut()
                goToLogin()
            }
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
