package com.mcqapp.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

/** Copy granularity while reading, independent of the file's size. */
private const val COPY_CHUNK_BYTES = 64 * 1024

/** Largest import file the app will read into memory. */
const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024

/**
 * Raised when a pick exceeds [limit].
 *
 * The file cannot be read to find out how big it really is, so [atLeastBytes]
 * is what we managed to copy before giving up — enough to tell the user which
 * side of the limit they are on, which the old bare "too large" never did.
 */
class ImportTooLargeException(
    val limit: Long,
    val atLeastBytes: Long = 0L
) : IOException("That file is larger than ${limit / (1024 * 1024)} MB.")

/**
 * Reads at most [limit] bytes, failing rather than growing without bound.
 *
 * `InputStream.readBytes()` sizes itself from `available()`, which a content
 * provider may report as the whole file, so a hostile or merely enormous pick
 * was allocated on the caller's thread before anything could object. Reading
 * in fixed chunks means the cap is enforced while copying, and the peak
 * allocation is the limit itself.
 */
/**
 * The message shown when a pick is over the cap.
 *
 * Images are inlined as base64, which adds about a third to every picture, so
 * a bank that is nowhere near 64 MB of prose can still trip this. Saying that
 * is more use than repeating the limit.
 */
fun tooLargeMessage(atLeastBytes: Long, limit: Long = MAX_IMPORT_BYTES): String {
    val limitMb = limit / (1024 * 1024)
    val sizeMb = ((atLeastBytes + 512 * 1024) / (1024 * 1024)).coerceAtLeast(1)
    return "That file is over the $limitMb MB import limit (at least $sizeMb MB). " +
        "Import the bank in parts, or drop the pictures — they are stored inside the " +
        "file and add about a third to its size."
}

fun readBounded(stream: InputStream, limit: Long = MAX_IMPORT_BYTES): ByteArray {
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    val out = java.io.ByteArrayOutputStream(minOf(limit, COPY_CHUNK_BYTES.toLong()).toInt())
    var total = 0L
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > limit) throw ImportTooLargeException(limit, total)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

/**
 * [stream] with [readBounded]'s cap enforced as it is read, for a caller that
 * consumes the bytes instead of keeping them: reading past [limit] fails the
 * same way, and nothing is buffered on the way.
 */
fun boundedStream(stream: InputStream, limit: Long = MAX_IMPORT_BYTES): InputStream =
    object : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val b = stream.read()
            if (b >= 0) counted(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = stream.read(b, off, len)
            if (read > 0) counted(read.toLong())
            return read
        }

        override fun close() {
            stream.close()
        }

        private fun counted(bytes: Long) {
            total += bytes
            if (total > limit) throw ImportTooLargeException(limit, total)
        }
    }

/** The first [bytes] of a stream, and whether anything followed them. */class Prefix(val bytes: ByteArray, val truncated: Boolean)

/**
 * Reads at most [limit] bytes without failing, reporting whether the stream
 * held anything more. The picker uses this to keep a small bank in memory
 * while handing a large one to the streaming import by `Uri`: only a bounded
 * prefix is ever allocated, so a provider cannot size the allocation.
 */
fun readPrefix(stream: InputStream, limit: Long): Prefix {
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    val out = java.io.ByteArrayOutputStream(minOf(limit, COPY_CHUNK_BYTES.toLong()).toInt())
    var total = 0L
    while (total < limit) {
        val want = minOf(chunk.size.toLong(), limit - total).toInt()
        val read = stream.read(chunk, 0, want)
        if (read < 0) return Prefix(out.toByteArray(), truncated = false)
        total += read
        out.write(chunk, 0, read)
    }
    val extra = stream.read()
    return Prefix(out.toByteArray(), truncated = extra >= 0)
}

/**
 * Prefers the provider's display name over a [Uri.getLastPathSegment] id.
 *
 * A picked document's last path segment is the provider's opaque row id, not
 * a name: the Downloads provider hands back "msf:1001050295", which became
 * the paper title when it was used as one. The display name wins when it is
 * present and not blank; the id survives only as the last resort, and a
 * blank answer from either side becomes null so the caller can fall back.
 */
fun pickDisplayName(displayName: String?, lastPathSegment: String?): String? =
    displayName?.trim()?.takeIf { it.isNotBlank() }
        ?: lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }

/**
 * The file's real name, as the provider labels it — "Physics Bank.docx".
 *
 * The display name comes from [OpenableColumns.DISPLAY_NAME]; a provider that
 * refuses the query or answers blank falls back to the last path segment.
 */
fun ContentResolver.displayName(uri: Uri): String? {
    val fromColumn = try {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        // A provider may reject the projection; the id is still a usable hint.
        null
    }
    return pickDisplayName(fromColumn, uri.lastPathSegment)
}
