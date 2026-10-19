package com.mcqapp.data.export

import java.io.Closeable
import java.io.File
import java.io.OutputStream

/**
 * The pictures one export embeds, decoded once and spooled to temp files.
 *
 * Every rich format here has to *name* its media before the body that
 * references it — `word/media` entries and their relationship ids, the zip's
 * `images/`, Anki's manifest — so the bytes cannot be written as they are met:
 * the body is one open archive entry at a time, and a zip's entries cannot be
 * interleaved. Holding them instead is a paper's worth of pictures in memory
 * (a thousand 200 KB images is 200 MB, on a device already holding the bank).
 * The spool keeps one image at a time and hands each one back to the format in
 * the order it wants.
 *
 * The temp files sit in the app's cache and are removed by [close]; use it in a
 * `finally` so a failed export leaves nothing behind. A process killed mid-way
 * skips that, which is what the startup sweep in `AnkiTempFiles` is for.
 */
internal class MediaSpool : Closeable {

    /**
     * A directory of our own: `createTempFile` is the portable way to get a
     * unique name, so the file it makes is dropped and re-created as a
     * directory. Every entry name is encoded into one file name (see
     * [fileName]), so no entry can escape it. Created on the first picture, so
     * an export without images leaves nothing behind at all.
     */
    private var dir: File? = null

    private val directory: File
        get() = dir ?: File.createTempFile("mcq-media", "").let {
            it.delete()
            it.mkdirs()
            it.also { made -> dir = made }
        }

    /** Spooled entries by name, in the order they were first met. */
    private val files = LinkedHashMap<String, File>()

    /** How many pictures are spooled. Stays readable after [close]. */
    val size: Int get() = files.size

    /** True when nothing was spooled, so the format writes no media at all. */
    val isEmpty: Boolean get() = files.isEmpty()

    /** The spooled names, in the order the body first met them. */
    val names: List<String> get() = files.keys.toList()

    /** Spools [bytes] under [name]; a name already spooled is left alone. */
    fun add(name: String, bytes: ByteArray) {
        if (files.containsKey(name)) return
        val file = File(directory, fileName(name))
        file.outputStream().use { it.write(bytes) }
        files[name] = file
    }

    /**
     * The one file name [name] gets in the spool directory. Entry names are
     * paths ("images/img001.png") and a file name may not be one, so the
     * separator is percent-encoded — and "%" with it, first, so the mapping
     * stays injective: without that, "a/b" and a literal "a%2Fb" would still
     * describe the same file, which is how `name.replace('/', '-')` let
     * "a/b" and "a-b" alias each other and hand back the wrong bytes.
     */
    private fun fileName(name: String): String =
        name.replace("%", "%25").replace("/", "%2F")

    /** Streams the picture spooled as [name] into [out]. */
    fun copyTo(name: String, out: OutputStream) {
        files.getValue(name).inputStream().use { it.copyTo(out) }
    }

    /** Removes the spool. The names and the count stay readable. */
    override fun close() {
        dir?.deleteRecursively()
        dir = null
    }
}
