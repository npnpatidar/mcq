package com.mcqapp.util

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
