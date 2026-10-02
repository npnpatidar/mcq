package com.mcqapp.util

import java.io.IOException
import java.io.InputStream

/** Copy granularity while reading, independent of the file's size. */
private const val COPY_CHUNK_BYTES = 64 * 1024

/** Largest import file the app will read into memory. */
const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024

class ImportTooLargeException(val limit: Long) :
    IOException("That file is larger than ${limit / (1024 * 1024)} MB.")

/**
 * Reads at most [limit] bytes, failing rather than growing without bound.
 *
 * `InputStream.readBytes()` sizes itself from `available()`, which a content
 * provider may report as the whole file, so a hostile or merely enormous pick
 * was allocated on the caller's thread before anything could object. Reading
 * in fixed chunks means the cap is enforced while copying, and the peak
 * allocation is the limit itself.
 */
fun readBounded(stream: InputStream, limit: Long = MAX_IMPORT_BYTES): ByteArray {
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    val out = java.io.ByteArrayOutputStream(minOf(limit, COPY_CHUNK_BYTES.toLong()).toInt())
    var total = 0L
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > limit) throw ImportTooLargeException(limit)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
