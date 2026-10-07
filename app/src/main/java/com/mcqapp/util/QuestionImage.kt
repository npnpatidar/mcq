package com.mcqapp.util

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
fun QuestionImage(
    src: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    if (src == null) return
    if (src.startsWith("data:image")) {
        // Decode off the main thread: large photos stalled list scrolling.
        // null = still loading, Failed = undecodable (shows the old fallback).
        val decoded by produceState<DecodeResult>(DecodeResult.Loading, src) {
            value = withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
                    // Subsampled decode: an oversized payload must not be
                    // expanded to full size before anything can reject it.
                    val bitmap: Bitmap? = decodeBounded(bytes, MAX_PREVIEW_DIMENSION)
                    if (bitmap != null) DecodeResult.Ready(bitmap) else DecodeResult.Failed
                } catch (e: Throwable) {
                    // Throwable, not Exception: Base64 and BitmapFactory both
                    // raise OutOfMemoryError on a hostile image.
                    DecodeResult.Failed
                }
            }
        }
        val bitmap = (decoded as? DecodeResult.Ready)?.bitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                contentScale = ContentScale.Fit
            )
        } else if (decoded is DecodeResult.Failed) {
            Text("Invalid image data", modifier = modifier)
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
                .heightIn(max = 240.dp),
            contentScale = ContentScale.Fit
        )
    }
}

private sealed interface DecodeResult {
    data object Loading : DecodeResult
    data class Ready(val bitmap: Bitmap) : DecodeResult
    data object Failed : DecodeResult
}
