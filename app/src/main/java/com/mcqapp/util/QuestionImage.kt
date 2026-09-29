package com.mcqapp.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                    val bitmap: Bitmap? =
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bitmap != null) DecodeResult.Ready(bitmap) else DecodeResult.Failed
                } catch (e: Exception) {
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
