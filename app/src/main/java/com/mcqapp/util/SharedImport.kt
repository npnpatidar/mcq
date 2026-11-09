package com.mcqapp.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/** Subdirectory of `cacheDir` for files copied from a `SEND` intent. */
const val IMPORTS_DIR_NAME = "imports"

/** File name for a `SEND` share that carried only `EXTRA_TEXT`. */
const val SEND_TEXT_FILE_NAME = "shared-text.json"

/**
 * One file shared to the app: the copy in `cacheDir/imports` (one-shot
 * grants do not survive process death, so the copy is the route's input)
 * and the original display name (the paper title for a Word bank).
 */
data class SharedImportRequest(
    val uri: Uri,
    val displayName: String?
)

/**
 * `SEND` imports waiting for the nav host. `MainActivity` requests one after
 * copying the stream; `McqNavHost` consumes it into the existing import
 * flow. The error channel carries a copy failure the router cannot retry
 * from the copy (too large, unreadable) as a message for the user.
 */
object SharedImport {
    private val _pending = MutableStateFlow<SharedImportRequest?>(null)
    val pending: StateFlow<SharedImportRequest?> = _pending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun request(uri: Uri, displayName: String?) {
        _pending.value = SharedImportRequest(uri, displayName)
    }

    fun requestError(message: String) {
        _error.value = message
    }

    fun consume(): SharedImportRequest? {
        val request = _pending.value
        _pending.value = null
        return request
    }

    fun consumeError(): String? {
        val message = _error.value
        _error.value = null
        return message
    }
}

sealed interface SendCopyResult {
    data class Ready(val request: SharedImportRequest) : SendCopyResult
    data class Failed(val message: String) : SendCopyResult
}

/**
 * The `EXTRA_STREAM` uri of a `SEND` intent, or null. Typed on API 33+, the
 * deprecated untyped call below it (the app's `minSdk` is 26).
 */
@Suppress("DEPRECATION")
fun sendStreamUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

/**
 * Immediately copies a `SEND` file into `cacheDir/imports` and returns the
 * copy's request. Runs on `Dispatchers.IO`: the stream is copied whole (at
 * most [MAX_IMPORT_BYTES], enforced by [copyBounded]) so the router never
 * depends on the sender's one-shot grant. A share that carried only
 * `EXTRA_TEXT` is written to the same directory as UTF-8 JSON.
 */
fun copySendToCache(context: Context, intent: Intent): SendCopyResult {
    val streamUri = sendStreamUri(intent)
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    val dir = File(context.cacheDir, IMPORTS_DIR_NAME).apply { mkdirs() }

    if (streamUri == null) {
        if (text.isNullOrBlank()) {
            return SendCopyResult.Failed("Could not read the file.")
        }
        val file = File(dir, sanitizeFileName(SEND_TEXT_FILE_NAME))
        return try {
            FileOutputStream(file).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            Logger.i("SEND", "Copied shared text (${file.length()} bytes)")
            SendCopyResult.Ready(SharedImportRequest(Uri.fromFile(file), SEND_TEXT_FILE_NAME))
        } catch (e: Exception) {
            file.delete()
            Logger.e("SEND", "Copying shared text failed", e)
            SendCopyResult.Failed("Could not read the file.")
        }
    }

    val resolver = context.contentResolver
    val displayName = resolver.displayName(streamUri)
    val safeName = sanitizeFileName(displayName ?: "shared-import")
    val file = File(dir, safeName)
    val input = try {
        resolver.openInputStream(streamUri)
    } catch (e: Exception) {
        null
    } ?: return SendCopyResult.Failed("Could not read the file.")

    return try {
        FileOutputStream(file).use { out -> copyBounded(input, out) }
        Logger.i("SEND", "Copied shared file '$safeName' (${file.length()} bytes)")
        SendCopyResult.Ready(SharedImportRequest(Uri.fromFile(file), displayName))
    } catch (e: ImportTooLargeException) {
        file.delete()
        Logger.e("SEND", "Shared file too large", e)
        SendCopyResult.Failed(tooLargeMessage(e.atLeastBytes))
    } catch (e: Exception) {
        file.delete()
        Logger.e("SEND", "Copying shared file failed", e)
        SendCopyResult.Failed("Could not read the file.")
    }
}
