package com.mcqapp.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * Streams the archive: [visit] is handed each entry's stream while that
     * entry is open, under the same entry-count, per-entry and total-expansion
     * caps [unzip] applies — counted from the decompressed bytes as they are
     * read, never from the archive's own metadata.
     *
     * The entry must be consumed before [visit] returns; anything it left is
     * drained afterwards so the next entry can be opened. Nothing beyond the
     * caller's own working set is held, which is the point: a reader can take
     * a 200 MB member out of an archive without the archive, or the member,
     * ever existing as a byte array.
     */
    suspend fun stream(
        input: InputStream,
        visit: suspend (name: String, entry: InputStream) -> Unit
    ) {
        // A parser may read an entry on a thread of its own, so the running
        // total has to be safe to touch from either of them.
        val total = AtomicLong()
        var entries = 0
        ZipInputStream(input.buffered(CHUNK_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    if (entries >= MAX_ENTRIES) {
                        throw ZipLimitException("Archive has more than $MAX_ENTRIES files.")
                    }
                    entries++
                    val counted = CountingEntry(zip, entry.name) { read ->
                        if (total.addAndGet(read) > MAX_TOTAL_BYTES) {
                            throw ZipLimitException(
                                "Archive expands to more than " +
                                    "${MAX_TOTAL_BYTES / (1024 * 1024)} MB."
                            )
                        }
                    }
                    visit(entry.name, counted)
                    counted.drain()
                } finally {
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * [ZipInputStream]'s view of one entry, with the per-entry cap enforced on
     * the way past and every byte reported to the archive's running total.
     * [close] is deliberately left alone: the entry is held by the archive the
     * caller still owns, so closing this would close the archive under it.
     */
    private class CountingEntry(
        private val delegate: InputStream,
        private val name: String,
        private val onRead: (Long) -> Unit
    ) : InputStream() {

        private var inEntry = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) counted(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) counted(read.toLong())
            return read
        }

        private fun counted(bytes: Long) {
            inEntry += bytes
            if (inEntry > MAX_ENTRY_BYTES) {
                throw ZipLimitException(
                    "'$name' expands to more than ${MAX_ENTRY_BYTES / (1024 * 1024)} MB."
                )
            }
            onRead(bytes)
        }

        /** Whatever [visit] left unread, so the next entry can be opened. */
        fun drain() {
            val chunk = ByteArray(CHUNK_BYTES)
            while (read(chunk) >= 0) {
                // Counted and capped like any other read; the bytes are dropped.
            }
        }
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
