package com.mcqapp.data.anki

import com.mcqapp.data.export.imageExtension
import com.mcqapp.data.export.parseDataUri

/**
 * Collects image data while rewriting a field into Anki HTML.
 *
 * Data URIs are decoded once, tracked for deduplication and emitted as numeric
 * media entries; remote URLs are left as-is because Anki can load them from the
 * network directly.
 */
class AnkiMediaPool {

    /** numeric zip name -> original filename for the Anki collection's media map. */
    private val mediaMap = LinkedHashMap<String, String>()

    /** numeric zip name -> image bytes. */
    private val mediaBytes = LinkedHashMap<String, ByteArray>()

    /** data URI -> numeric zip name already assigned (dedupe). */
    private val assigned = HashMap<String, String>()

    val entries: Map<String, ByteArray> get() = mediaBytes

    /** numeric zip name -> original filename (the Anki media manifest). */
    val manifest: Map<String, String> get() = mediaMap

    /** HTML-escapes [text] and appends its image as a self-contained `<img>`. */
    fun htmlField(text: String, imageSrc: String?): String =
        escapeHtml(text) + imageElement(imageSrc)

    private fun imageElement(src: String?): String {
        if (src.isNullOrBlank()) return ""
        val dataUri = parseDataUri(src)
        if (dataUri != null) {
            val numeric = assigned.getOrPut(src) {
                val name = "img-${mediaBytes.size}.${imageExtension(dataUri.mimeType)}"
                val id = mediaBytes.size.toString()
                mediaMap[id] = name
                mediaBytes[id] = dataUri.bytes
                id
            }
            return " " + AnkiPackageWriter.imageTag(numeric)
        }
        // A remote URL: keep it, Anki will fetch it over the network.
        return " " + AnkiPackageWriter.imageTag(src)
    }

    companion object {
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