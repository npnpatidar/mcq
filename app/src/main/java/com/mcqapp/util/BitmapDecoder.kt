package com.mcqapp.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Preview images are drawn at most this many pixels on their longest edge. */
const val MAX_PREVIEW_DIMENSION = 1600

/** Exported/embedded images keep a little more detail. */
const val MAX_EXPORT_DIMENSION = 2400

/**
 * The largest power-of-two subsample that still leaves the *longest* edge at
 * or above [reqDimension].
 *
 * `BitmapFactory` decodes the *full* image into an ARGB_8888 buffer before any
 * scaling, so a small base64 payload describing a 20000×20000 PNG asks for
 * 1.6 GB and dies with an OutOfMemoryError — which is an `Error`, so the
 * surrounding `catch (e: Exception)` never saw it.
 *
 * Keyed on the longest edge rather than both edges: a 3200×2400 photo still
 * needs halving to respect a 1600 px cap, even though its short edge would
 * not survive a further halving.
 */
fun calculateInSampleSize(width: Int, height: Int, reqDimension: Int): Int {
    if (width <= 0 || height <= 0) return 1
    val req = reqDimension.coerceAtLeast(1)
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= req) {
        sample *= 2
    }
    return sample
}

/**
 * Decodes [bytes] subsampled to about [maxDimension] on its longest edge.
 *
 * Catches `Throwable` on purpose: an oversized or malformed image raises
 * `OutOfMemoryError`, and letting that escape a `produceState` coroutine kills
 * the process rather than showing the caller's fallback.
 */
fun decodeBounded(bytes: ByteArray, maxDimension: Int = MAX_PREVIEW_DIMENSION): Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } catch (e: Throwable) {
        Logger.e("IMAGE", "Failed to decode ${bytes.size} bytes", e)
        null
    }
}
