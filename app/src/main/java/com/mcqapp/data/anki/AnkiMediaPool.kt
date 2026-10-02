package com.mcqapp.data.anki

import com.mcqapp.data.export.imageExtension
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.renderInlineHtml
import com.mcqapp.domain.ContentElement

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

    /**
     * Renders content elements to Anki field HTML: text is escaped, MathML is
     * embedded as-is, tables become `<table>` blocks, and images are stored in
     * the media pool and referenced by filename. [legacyImage] is the separate
     * image field of a pre-rich-content question, appended when present.
     */
    fun elementsToHtml(elements: List<ContentElement>, legacyImage: String? = null): String =
        elements.joinToString("") { elementToHtml(it) } + imageElement(legacyImage)

    private fun elementToHtml(element: ContentElement): String = when (element) {
        // Inline tags render as formatting in Anki's webview; the shared
        // helper escapes anything else.
        is ContentElement.TextElement -> renderInlineHtml(element.text)
        is ContentElement.ImageElement -> imageElement(element.src)
        is ContentElement.TableElement -> tableToHtml(element)
        is ContentElement.MathElement -> element.mathml
    }

    private fun tableToHtml(table: ContentElement.TableElement): String {
        val sb = StringBuilder("<table border=\"1\">")
        table.rows.forEach { row ->
            sb.append("<tr>")
            row.forEach { cell ->
                sb.append("<td>").append(renderInlineHtml(cell)).append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

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
