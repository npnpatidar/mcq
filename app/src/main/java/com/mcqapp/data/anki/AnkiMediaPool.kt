package com.mcqapp.data.anki

import com.mcqapp.data.export.imageExtension
import com.mcqapp.data.export.parseDataUri

/**
 * Collects image data while rewriting a field into Anki HTML.
 *
 * Data URIs are decoded once and stored under a generated filename. The note
 * field references that *filename* while the zip entry keeps a numeric name,
 * which is the layout Anki's own exporter uses: on import Anki builds its media
 * map keyed by filename and rewrites `src` from what the field says, so a
 * numeric `src` would never resolve. Remote URLs are left as-is because Anki
 * loads them from the network directly.
 */
class AnkiMediaPool {

    /** numeric zip name -> filename for the Anki collection's media map. */
    private val mediaMap = LinkedHashMap<String, String>()

    /** numeric zip name -> image bytes. */
    private val mediaBytes = LinkedHashMap<String, ByteArray>()

    /** data URI -> filename already assigned (dedupe). */
    private val assigned = HashMap<String, String>()

    val entries: Map<String, ByteArray> get() = mediaBytes

    /** numeric zip name -> filename (the Anki media manifest). */
    val manifest: Map<String, String> get() = mediaMap

    /** HTML-escapes [text] and appends its image as a self-contained `<img>`. */
    fun htmlField(text: String, imageSrc: String?): String =
        escapeHtml(text) + imageElement(imageSrc)

    /** Escapes [text] without an image; for a field that is purely markup. */
    fun html(text: String): String = escapeHtml(text)

    private fun imageElement(src: String?): String {
        if (src.isNullOrBlank()) return ""
        val dataUri = parseDataUri(src)
        if (dataUri != null) {
            val filename = assigned.getOrPut(src) {
                val name = safeName(mediaBytes.size, imageExtension(dataUri.mimeType))
                mediaMap[mediaBytes.size.toString()] = name
                mediaBytes[mediaBytes.size.toString()] = dataUri.bytes
                name
            }
            return " " + AnkiPackageWriter.imageTag(filename)
        }
        // A remote URL: keep it, Anki will fetch it over the network.
        return " " + AnkiPackageWriter.imageTag(src)
    }

    companion object {
        /**
         * A filename Anki will accept: no path separators or control characters,
         * not starting with a dot, and deliberately not starting with `_` or
         * `latex-` because Anki treats those as static files it does not rewrite.
         */
        fun safeName(index: Int, extension: String): String {
            val ext = extension.trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "jpg" }
            return "mcqapp-$index.$ext"
        }

        fun escapeHtml(text: String): String = buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    '\n' -> append(AnkiPackageWriter.NEWLINE_BREAK)
                    '\r' -> {}
                    else -> append(ch)
                }
            }
        }
    }
}
