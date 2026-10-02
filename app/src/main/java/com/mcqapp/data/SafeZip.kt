package com.mcqapp.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Raised when an archive exceeds a size or entry-count limit. */
class ZipLimitException(message: String) : IOException(message)

/**
 * Bounded ZIP extraction shared by the `.docx` and `.apkg` readers.
 *
 * Both readers used to buffer every entry into a map with no limit on entry
 * count, per-entry size or compression ratio, so a small archive could expand
 * to hundreds of megabytes and kill the process. Entry sizes are counted from
 * what is actually decompressed rather than trusting the archive's own
 * metadata, which a hostile file controls.
 */
object SafeZip {

    const val MAX_ENTRIES: Int = 4096
    const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    private const val CHUNK_BYTES = 64 * 1024

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        if (out.size >= MAX_ENTRIES) {
                            throw ZipLimitException("Archive has more than $MAX_ENTRIES files.")
                        }
                        val data = readEntry(zip, entry.name)
                        total += data.size
                        if (total > MAX_TOTAL_BYTES) {
                            throw ZipLimitException(
                                "Archive expands to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB."
                            )
                        }
                        out[entry.name] = data
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun readEntry(zip: ZipInputStream, name: String): ByteArray {
        val sink = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = zip.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) {
                throw ZipLimitException(
                    "'$name' expands to more than ${MAX_ENTRY_BYTES / (1024 * 1024)} MB."
                )
            }
            sink.write(chunk, 0, read)
        }
        return sink.toByteArray()
    }
}
