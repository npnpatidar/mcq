package com.mcqapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ImageUtils {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 80

    fun encodeImageUri(context: Context, uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                // The pick is not trusted: this used to be readBytes(), which
                // sizes itself from `available()` and materialised a 40 MB
                // photo in one ball before anything could object. The cap is
                // the import cap — an image over it can never fit the bank
                // that inlines its images anyway.
                readBounded(it)
            } ?: return null
            // Decode straight to the target size instead of materialising a
            // full-resolution photo only to scale it down afterwards.
            val bitmap = uprightedBitmap(bytes, MAX_DIMENSION) ?: return null
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

    /**
     * Decode bounded AND upright: a gallery/camera pick often carries JPEG
     * EXIF orientation != 1, which `BitmapFactory` ignores, so the stored
     * bytes — and every export and re-import that follows — came out rotated
     * 90/180.
     *
     * Applied ONLY on the encode/store path on purpose: `decodeBounded` also
     * serves the on-screen previews and the PDF writer, which read what the
     * bank already stores, and what the bank stores is oriented upright from
     * here on — rotating there as well would lay every previously-stored
     * image on its side.
     */
    internal fun uprightedBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bitmap = decodeBounded(bytes, maxDimension) ?: return null
        val upright = applyExif(bytes, bitmap)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** The stored-marker EXIF orientation of an encoded image; 1 unless known. */
    internal fun exifOrientation(bytes: ByteArray): Int = try {
        android.media.ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Throwable) {
        android.media.ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * One orientation value → the display transform as the reference
     * implementation (`PIL.ImageOps.exif_transpose`) applies it: mirror
     * horizontally first when [mirrorFirst], then rotate [rotationDegrees]
     * clockwise. Generated fixtures committed under test/resources/exif
     * (a red/left blue/right image stored with each tag) pin the mapping
     * against that reference rather than against memory.
     */
    internal data class ExifFix(val rotationDegrees: Int, val mirrorFirst: Boolean)

    internal fun exifFix(orientation: Int): ExifFix? = when (orientation) {
        1, 0 -> null
        2 -> ExifFix(0, true)
        3 -> ExifFix(180, false)
        4 -> ExifFix(180, true)
        5 -> ExifFix(270, true)
        6 -> ExifFix(90, false)
        7 -> ExifFix(90, true)
        8 -> ExifFix(270, false)
        else -> null
    }

    internal fun applyExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val fix = exifFix(exifOrientation(bytes)) ?: return bitmap
        var out = bitmap
        if (fix.mirrorFirst) {
            out = Bitmap.createBitmap(
                out, 0, 0, out.width, out.height,
                // Pivot form on purpose: mirroring about the bitmap centre maps
                // the source rectangle exactly onto itself, so no coordinate
                // normalisation is left to guesswork.
                Matrix().apply { postScale(-1f, 1f, out.width / 2f, out.height / 2f) },
                true
            )
        }
        if (fix.rotationDegrees != 0) {
            out = Bitmap.createBitmap(
                out, 0, 0, out.width, out.height,
                Matrix().apply { postRotate(fix.rotationDegrees.toFloat()) },
                true
            )
        }
        return out
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
