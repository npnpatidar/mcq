package com.mcqapp.data.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mcqapp.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Shrinks oversized embedded images at import time so multi-MB banks don't
 * bloat the database (a 10k-image stress file was 26 MB of data URIs).
 *
 * Only `data:` URIs are touched — remote URLs stay references. Dimensions
 * shrink to [maxDimension] on the long edge; the format is preserved (PNG
 * stays PNG, anything else becomes JPEG). Anything unparseable passes
 * through untouched: downscaling must never break an import.
 *
 * NOTE: the import duplicate hash is computed on the ORIGINAL bytes (see
 * Importer), so re-importing the same file still matches as Duplicate.
 */
object ImageDownscale {

    const val DEFAULT_MAX_DIMENSION = 1280
    const val JPEG_QUALITY = 85

    fun parseDataUri(src: String?): Pair<String, ByteArray>? {
        if (src == null || !src.startsWith("data:")) return null
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val meta = src.substring(5, comma)
        if (!meta.contains("base64")) return null
        val mime = meta.substringBefore(';')
        return try {
            val bytes = java.util.Base64.getMimeDecoder().decode(src.substring(comma + 1))
            mime to bytes
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Long edge capped at [maxDimension]; smaller images keep their size. */
    fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longEdge = maxOf(width, height)
        if (longEdge <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longEdge
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    fun encodeDataUri(mime: String, bytes: ByteArray): String =
        "data:$mime;base64," + java.util.Base64.getMimeEncoder().encodeToString(bytes)

    fun downscaleDataUri(
        src: String?,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): String? {
        if (src == null) return null
        val (mime, bytes) = parseDataUri(src) ?: return src
        if (!mime.startsWith("image/")) return src
        return try {
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return src
            val (tw, th) = targetSize(original.width, original.height, maxDimension)
            if (tw == original.width && th == original.height) {
                original.recycle()
                return src
            }
            val scaled = Bitmap.createScaledBitmap(original, tw, th, true)
            original.recycle()
            val keepPng = mime == "image/png"
            val out = ByteArrayOutputStream()
            val ok = scaled.compress(
                if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                JPEG_QUALITY,
                out
            )
            scaled.recycle()
            if (!ok) return src
            val outMime = if (keepPng) "image/png" else "image/jpeg"
            val result = encodeDataUri(outMime, out.toByteArray())
            Logger.d("DOWNSCALE", "Shrunk image ${bytes.size / 1024}KB -> ${result.length / 1024}KB " +
                "(${tw}x$th, $outMime)")
            result
        } catch (e: Exception) {
            Logger.w("DOWNSCALE", "Passthrough on failure: ${e.message}")
            src
        } catch (e: OutOfMemoryError) {
            Logger.w("DOWNSCALE", "Passthrough on OOM")
            src
        }
    }
}
