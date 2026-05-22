package com.example.fixup.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object ImageUtils {
    fun compressImage(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        return stream.toByteArray()
    }
}
