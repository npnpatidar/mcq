package com.mcqapp.data.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mcqapp.util.Logger
import com.mcqapp.util.decodeBounded
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
 * NOTE: images at or below [maxDimension] pass through byte-identical, so
 * the duplicate hash (computed on the scaled DTO, see Importer) still
 * matches across re-imports of the same file.
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
            // BitmapFactory decodes the *full* image into an ARGB_8888 buffer
            // before any scaling, so a small payload describing a 20000x20000
            // PNG asks for 1.6GB — the peak memory is the bug here. The
            // source size is read first (a bounds pass costs no pixels) and
            // the pixels themselves are decoded subsampled.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return src
            // The decision and the target are taken on the SOURCE size: a
            // subsampled decode lands on (or just above) maxDimension, so a
            // source that is an exact multiple of it — 2560 at 1280 decodes
            // to 1280 — would be passed through unchanged if the decoded size
            // were compared instead.
            val (tw, th) = targetSize(bounds.outWidth, bounds.outHeight, maxDimension)
            if (tw == bounds.outWidth && th == bounds.outHeight) return src
            val original = decodeBounded(bytes, maxDimension) ?: return src
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
