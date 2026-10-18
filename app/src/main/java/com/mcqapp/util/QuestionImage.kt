package com.mcqapp.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mcqapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether http(s) images may be fetched. Provided once at the nav root from
 * the stored setting; the default is off so any host that forgets to provide
 * it stays private.
 */
val LocalLoadRemoteImages = compositionLocalOf { false }

/** True when Coil would fetch [src] over the network instead of reading it locally. */
internal fun isRemoteImageSrc(src: String): Boolean =
    src.startsWith("http://", ignoreCase = true) ||
        src.startsWith("https://", ignoreCase = true)

/** Largest height an inline image may take, matching [QuestionImage]. */
private val MAX_PREVIEW_HEIGHT = 240.dp

@Composable
fun QuestionImage(
    src: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    if (src == null) return
    if (src.startsWith("data:image")) {
        // Decode off the main thread: large photos stalled list scrolling.
        // A decoded bitmap is kept in a bounded cache: a row that scrolls out
        // and back used to re-decode its base64 payload and bitmap every
        // time, which is most of what made a maths-and-image list stutter.
        val decoded by produceState<DecodeResult>(
            initialValue = DecodedImageCache.get(src)?.let { DecodeResult.Ready(it) }
                ?: DecodeResult.Loading,
            src
        ) {
            // Already decoded: reuse it and do no work at all. Without this
            // the producer would decode the payload again on every return to
            // the row, which is exactly the cost the cache exists to avoid.
            val cached = DecodedImageCache.get(src)
            if (cached != null) {
                value = DecodeResult.Ready(cached)
                return@produceState
            }
            value = withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
                    // Read the intrinsic size first: it costs almost nothing
                    // and lets the row reserve the image's space before the
                    // pixels are ready, so the card does not grow under the
                    // scroll thumb when the decode lands.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        value = DecodeResult.Measured(bounds.outWidth.toFloat() / bounds.outHeight)
                    }
                    // Subsampled decode: an oversized payload must not be
                    // expanded to full size before anything can reject it.
                    val bitmap: Bitmap? = decodeBounded(bytes, MAX_PREVIEW_DIMENSION)
                    if (bitmap != null) {
                        DecodedImageCache.put(src, bitmap)
                        DecodeResult.Ready(bitmap)
                    } else {
                        DecodeResult.Failed
                    }
                } catch (e: Throwable) {
                    // Throwable, not Exception: Base64 and BitmapFactory both
                    // raise OutOfMemoryError on a hostile image.
                    DecodeResult.Failed
                }
            }
        }
        when (val result = decoded) {
            is DecodeResult.Ready -> androidx.compose.foundation.Image(
                bitmap = result.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = MAX_PREVIEW_HEIGHT),
                contentScale = ContentScale.Fit
            )
            is DecodeResult.Measured -> reservedImageSpace(result.ratio, modifier)
            is DecodeResult.Failed -> Text("Invalid image data", modifier = modifier)
            // Still reading the header: nothing is committed to the row yet.
            DecodeResult.Loading -> Unit
        }
    } else if (isRemoteImageSrc(src) && !LocalLoadRemoteImages.current) {
        // Privacy default: never hand Coil a network URL while the setting
        // is off. Tell the reader the slot is not empty so a question that
        // says "see the image below" still makes sense.
        Text(
            text = stringResource(R.string.remote_image_hidden_turn_on_load_remote_images_in_settin),
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(src)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = MAX_PREVIEW_HEIGHT),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * Holds the space the decoded image will occupy: the same width-anchored
 * height [androidx.compose.foundation.Image] will take once it lands,
 * capped like the image itself. Bounded by the width the row actually
 * gives the image, which is why it needs the box's constraints.
 */
@Composable
private fun reservedImageSpace(ratio: Float, modifier: Modifier) {
    if (ratio <= 0f) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val height = (maxWidth / ratio).coerceAtMost(MAX_PREVIEW_HEIGHT)
        Spacer(Modifier.fillMaxWidth().height(height))
    }
}

private sealed interface DecodeResult {
    data object Loading : DecodeResult
    /** Intrinsic aspect ratio known, pixels still decoding. */
    data class Measured(val ratio: Float) : DecodeResult
    data class Ready(val bitmap: Bitmap) : DecodeResult
    data object Failed : DecodeResult
}

/**
 * Bounded LRU used for decoded inline images.
 *
 * Keyed by the full data URI (the question already holds that string, so no
 * copy is made) and bounded by total bitmap bytes, not entry count: one
 * photo can be much larger than a diagram, and the bitmap is what the
 * process is actually paying for.
 */
internal class SizeBoundedLru<K, V>(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val previous = entries.put(key, value)
        if (previous != null) bytes -= sizeOf(previous)
        bytes += sizeOf(value)
        val oldest = entries.entries.iterator()
        while ((entries.size > maxEntries || bytes > maxBytes) && oldest.hasNext()) {
            if (entries.size == 1) break
            val entry = oldest.next()
            bytes -= sizeOf(entry.value)
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}

private val DecodedImageCache = SizeBoundedLru<String, Bitmap>(
    maxEntries = 24,
    maxBytes = 8L * 1024 * 1024,
    sizeOf = { it.allocationByteCount.toLong() }
)
