package com.example.fixup.customer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Geocoder
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.fixup.databinding.ActivityPostRequestBinding
import com.example.fixup.utils.Constants
import com.example.fixup.utils.ImageUtils
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class PostRequestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostRequestBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var selectedBitmap: Bitmap? = null
    private var cameraImageUri: android.net.Uri? = null
    private var aiResult: AiResult? = null

    private var capturedLat: Double? = null
    private var capturedLon: Double? = null
    private var capturedLocationName: String? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private data class AiResult(
        val detected: String,
        val service: String,
        val icon: String,
        val confidence: Double,
        val priceMin: Int,
        val priceMax: Int,
        val priceRecommended: Int,
        val priceReason: String,
        val sampleCount: Int
    )

    // ── Activity result launchers ──────────────────────────────────────────────

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                val raw = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return@let

                val rotated = contentResolver.openInputStream(uri)?.use { stream ->
                    try {
                        val exif = ExifInterface(stream)
                        val degrees = when (exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )) {
                            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                        if (degrees != 0f) {
                            Bitmap.createBitmap(
                                raw, 0, 0, raw.width, raw.height,
                                Matrix().apply { postRotate(degrees) }, true
                            )
                        } else raw
                    } catch (e: Exception) {
                        raw
                    }
                } ?: raw

                selectedBitmap = rotated
                onImageSelected()
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                onImageSelected()
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else showPermissionDeniedDialog("Camera", "take photos of the damage")
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickImageLauncher.launch("image/*")
        else showPermissionDeniedDialog("Storage", "pick images from your gallery")
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) fetchAndShowLocation()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnCamera.setOnClickListener { handleCameraClick() }
        binding.btnGallery.setOnClickListener { handleGalleryClick() }
        binding.btnAnalyze.setOnClickListener { analyzeImage() }
        binding.btnPostRequest.setOnClickListener { postRequest() }

        binding.etCity.setText(Constants.DEFAULT_CITY)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Image selection ────────────────────────────────────────────────────────

    private fun handleCameraClick() {
        val perm = Manifest.permission.CAMERA
        when {
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED ->
                launchCamera()
            shouldShowRequestPermissionRationale(perm) ->
                showRationaleDialog(
                    "Camera Access Needed",
                    "FixUp needs camera access to photograph the damage."
                ) { cameraPermissionLauncher.launch(perm) }
            else ->
                cameraPermissionLauncher.launch(perm)
        }
    }

    private fun handleGalleryClick() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        when {
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED ->
                pickImageLauncher.launch("image/*")
            shouldShowRequestPermissionRationale(perm) ->
                showRationaleDialog(
                    "Storage Access Needed",
                    "FixUp needs storage access to pick images from your gallery."
                ) { storagePermissionLauncher.launch(perm) }
            else ->
                storagePermissionLauncher.launch(perm)
        }
    }

    private fun launchCamera() {
        try {
            val dir = getExternalFilesDir("Pictures") ?: filesDir
            val imageFile = File.createTempFile("fixup_", ".jpg", dir)
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
            takePictureLauncher.launch(cameraImageUri!!)
        } catch (e: IOException) {
            Toast.makeText(this, "Could not start camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onImageSelected() {
        binding.ivPreview.setImageBitmap(selectedBitmap)
        binding.tvImageHint.visibility = View.GONE
        binding.btnAnalyze.isEnabled = true
        clearResult()
    }

    private fun clearResult() {
        aiResult = null
        binding.cardResult.visibility = View.GONE
        binding.btnPostRequest.visibility = View.GONE
    }

    // ── AI Analysis ────────────────────────────────────────────────────────────

    private fun analyzeImage() {
        val bitmap = selectedBitmap ?: return
        val description = binding.etDescription.text.toString().trim().ifEmpty { "No description provided" }
        val city = binding.etCity.text.toString().trim().ifEmpty { Constants.DEFAULT_CITY }

        setAnalyzeLoading(true)
        val imageBytes = ImageUtils.compressImage(bitmap)

        Thread {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "image", "damage.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart("description", description)
                    .addFormDataPart("city", city)
                    .build()

                val request = Request.Builder()
                    .url("${Constants.FLASK_BASE_URL}${Constants.FLASK_PREDICT}")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            setAnalyzeLoading(false)
                            Toast.makeText(this, "Server error ${response.code} — check Flask logs", Toast.LENGTH_LONG).show()
                        }
                        return@use
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val result = AiResult(
                        detected         = json.getString("detected"),
                        service          = json.getString("service"),
                        icon             = json.optString("icon", "🔧"),
                        confidence       = json.getDouble("confidence"),
                        priceMin         = json.getInt("price_min"),
                        priceMax         = json.getInt("price_max"),
                        priceRecommended = json.getInt("price_recommended"),
                        priceReason      = json.getString("price_reason"),
                        sampleCount      = json.getInt("sample_count")
                    )
                    runOnUiThread {
                        setAnalyzeLoading(false)
                        aiResult = result
                        displayAiResult(result)
                        captureLocation()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setAnalyzeLoading(false)
                    Toast.makeText(this, "Analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun displayAiResult(result: AiResult) {
        val label = result.detected.replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
        val confPct = (result.confidence * 100).toInt()
        val fmt = "%,d"

        binding.tvDetectedClass.text   = "${result.icon} $label Detected"
        binding.tvDetectedService.text = "Suggested Service: ${result.service}"
        binding.tvConfidence.text      = "$confPct%"
        binding.confidenceBar.progress = confPct

        binding.tvPriceMin.text         = "PKR ${String.format(Locale.US, fmt, result.priceMin)}"
        binding.tvPriceMax.text         = "PKR ${String.format(Locale.US, fmt, result.priceMax)}"
        binding.tvPriceRecommended.text = "PKR ${String.format(Locale.US, fmt, result.priceRecommended)}"
        binding.tvPriceReason.text      = "\"${result.priceReason}\""
        binding.tvPriceSource.text      = if (result.sampleCount > 0)
            "Based on ${result.sampleCount} recent bids + AI analysis"
        else
            "Based on market rates + AI analysis"

        binding.cardResult.visibility     = View.VISIBLE
        binding.btnPostRequest.visibility = View.VISIBLE

        binding.nestedScroll.post {
            binding.nestedScroll.smoothScrollTo(0, binding.cardResult.top)
        }
    }

    // ── Post to Firestore ──────────────────────────────────────────────────────

    private fun postRequest() {
        val result = aiResult ?: return
        val bitmap = selectedBitmap ?: return
        val uid = auth.currentUser?.uid ?: return
        val description = binding.etDescription.text.toString().trim().ifEmpty { "No description provided" }
        val city = binding.etCity.text.toString().trim().ifEmpty { Constants.DEFAULT_CITY }

        setPostLoading(true)

        val imageBytes = ImageUtils.compressImage(bitmap)
        val storagePath = "images/$uid/${System.currentTimeMillis()}.jpg"
        val storageRef = storage.reference.child(storagePath)

        storageRef.putBytes(imageBytes)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception!!
                storageRef.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                val imageUrl = downloadUri.toString()
                fetchUserAndSave(uid, description, city, imageUrl, result)
            }
            .addOnFailureListener { e ->
                setPostLoading(false)
                Log.e("PostRequest", "Storage upload failed", e)
                AlertDialog.Builder(this)
                    .setTitle("Upload Failed")
                    .setMessage(e.message ?: "Unknown error. Check Logcat for details.")
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    private fun fetchUserAndSave(
        uid: String,
        description: String,
        city: String,
        imageUrl: String,
        result: AiResult
    ) {
        db.collection(Constants.COLLECTION_USERS).document(uid).get()
            .addOnSuccessListener { doc ->
                val customerName = doc.getString("name") ?: "Customer"
                val label = result.detected.replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

                val data = hashMapOf<String, Any?>(
                    "customerId"                to uid,
                    "customerName"              to customerName,
                    "title"                     to "$label repair in $city",
                    "description"               to description,
                    "imageUrl"                  to imageUrl,
                    "detectedClass"             to result.detected,
                    "detectedCategory"          to result.service,
                    "detectedConfidence"        to result.confidence,
                    "estimatedPriceMin"         to result.priceMin,
                    "estimatedPriceMax"         to result.priceMax,
                    "estimatedPriceRecommended" to result.priceRecommended,
                    "priceJustification"        to result.priceReason,
                    "priceSampleCount"          to result.sampleCount,
                    "city"                      to city,
                    "status"                    to "open",
                    "acceptedBidId"             to "",
                    "acceptedVendorId"          to "",
                    "latitude"                  to capturedLat,
                    "longitude"                 to capturedLon,
                    "locationName"              to (capturedLocationName ?: city),
                    "createdAt"                 to FieldValue.serverTimestamp(),
                    "completedAt"               to null
                )

                db.collection(Constants.COLLECTION_REQUESTS)
                    .add(data)
                    .addOnSuccessListener { docRef ->
                        setPostLoading(false)
                        val intent = Intent(this, BidsActivity::class.java).apply {
                            putExtra("requestId", docRef.id)
                            putExtra("requestTitle", data["title"] as String)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Request Posted!")
                            .setMessage("Your repair request has been posted successfully. Verified vendors in your area will now see it and can submit bids.")
                            .setPositiveButton("View Bids") { _, _ ->
                                startActivity(intent)
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    .addOnFailureListener { e ->
                        setPostLoading(false)
                        Toast.makeText(this, "Failed to post: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener {
                setPostLoading(false)
                Toast.makeText(this, "Could not load user data", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Location capture ───────────────────────────────────────────────────────

    private fun captureLocation() {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasPermission =
            ContextCompat.checkSelfPermission(this, fine)   == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) fetchAndShowLocation()
        else locationPermissionLauncher.launch(arrayOf(fine, coarse))
    }

    private fun fetchAndShowLocation() {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        capturedLat = location.latitude
                        capturedLon = location.longitude
                        capturedLocationName = reverseGeocode(capturedLat!!, capturedLon!!)
                        binding.tvLocationName.text = "📍 $capturedLocationName"
                        binding.tvLocationName.visibility = View.VISIBLE
                    }
                }
        } catch (_: SecurityException) { }
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

    // ── Permission helpers ─────────────────────────────────────────────────────

    private fun showRationaleDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionDeniedDialog(permissionName: String, usageDescription: String) {
        AlertDialog.Builder(this)
            .setTitle("$permissionName Permission Required")
            .setMessage("FixUp needs $permissionName access to $usageDescription. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Loading helpers ────────────────────────────────────────────────────────

    private fun setAnalyzeLoading(loading: Boolean) {
        binding.btnAnalyze.isEnabled    = !loading
        binding.btnCamera.isEnabled     = !loading
        binding.btnGallery.isEnabled    = !loading
        binding.layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun setPostLoading(loading: Boolean) {
        binding.btnPostRequest.isEnabled    = !loading
        binding.progressBarPost.visibility  = if (loading) View.VISIBLE else View.GONE
    }
}
