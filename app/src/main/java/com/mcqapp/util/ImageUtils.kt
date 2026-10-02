package com.mcqapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 80

    fun encodeImageUri(context: Context, uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            // Decode straight to the target size instead of materialising a
            // full-resolution photo only to scale it down afterwards.
            val bitmap = decodeBounded(bytes, MAX_DIMENSION) ?: return null
            val scaled = scaleBitmap(bitmap)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Throwable) {
            Logger.e("IMAGE", "Failed to encode image", e)
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / maxOf(width, height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
