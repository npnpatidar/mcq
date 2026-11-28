package com.mcqapp.data.anki

import com.mcqapp.data.export.MediaSpool
import com.mcqapp.data.export.chemElementText
import com.mcqapp.data.export.imageExtension
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.export.mediaKey
import com.mcqapp.data.export.parseDataUri
import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.renderInlineHtml
import com.mcqapp.data.sanitizeMathml
import com.mcqapp.domain.CellPart
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.tableCellParts
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.CellSegment
import com.mcqapp.util.cellSegments
import com.mcqapp.util.forEachCellImage
import com.mcqapp.util.mapCellImages
import com.mcqapp.util.portableCellImageSrc
import com.mcqapp.util.repairStoredMathml
import java.io.Closeable
import java.io.OutputStream

/**
 * Collects image data while rewriting a field into Anki HTML.
 *
 * Data URIs are decoded once and spooled to a temp file under a generated
 * filename. The note field references that *filename* while the zip entry keeps
 * a numeric name, which is the layout Anki's own exporter uses: on import Anki
 * builds its media map keyed by filename and rewrites `src` from what the field
 * says, so a numeric `src` would never resolve. Remote URLs are left as-is
 * because Anki loads them from the network directly.
 *
 * The bytes are spooled rather than kept ([MediaSpool]): a deck's pictures are
 * all named before the collection is zipped, so holding them would be the whole
 * export's images in memory. Use it in a `finally` — [close] removes the spool.
 */
class AnkiMediaPool : Closeable {

    /** numeric zip name -> filename for the Anki collection's media map. */
    private val mediaMap = LinkedHashMap<String, String>()

    /** The spooled media, numeric zip name -> temp file. */
    private val spool = MediaSpool()

    /** data URI digest -> filename already assigned (dedupe). */
    private val assigned = HashMap<String, String>()

    /** numeric zip name -> filename (the Anki media manifest). */
    val manifest: Map<String, String> get() = mediaMap

    /** How many media files the pool holds. Stays readable after [close]. */
    val size: Int get() = spool.size

    /** True when the export has media; Anki writes no `media` entry otherwise. */
    val isNotEmpty: Boolean get() = !spool.isEmpty

    /** The numeric zip names, in the order the notes met them. */
    val names: List<String> get() = spool.names

    /** Streams one spooled media entry into [out]. */
    fun copyTo(numeric: String, out: OutputStream) = spool.copyTo(numeric, out)

    override fun close() = spool.close()

    /** HTML-escapes [text] and appends its image as a self-contained `<img>`. */
    fun htmlField(text: String, imageSrc: String?): String =
        escapeHtml(text) + imageElement(imageSrc)

    /** Escapes [text] without an image; for a field that is purely markup. */
    fun html(text: String): String = escapeHtml(text)

    /**
     * Renders content elements to Anki field HTML: text keeps inline
     * formatting, MathML is normalized (bare text tokenized) so webviews
     * render it, tables become `<table>` blocks, and images are stored in
     * the media pool and referenced by filename. [legacyImage] is the separate
     * image field of a pre-rich-content question, appended when present.
     */
    fun elementsToHtml(elements: List<ContentElement>, legacyImage: String? = null): String =
        elements.joinToString("") { elementToHtml(it) } + imageElement(legacyImage)

    private fun elementToHtml(element: ContentElement): String = when (element) {
        // Inline tags render as formatting in Anki's webview; the shared
        // helper escapes anything else.
        is ContentElement.TextElement -> renderInlineHtml(element.text)
        // Bare text in mrow is invalid MathML (browsers drop it); the
        // shared normalizer tokenizes it into mi/mn/mo. A legacy bare run is
        // repaired to a full block first, and attributes are stripped so a
        // hostile stored fragment cannot reach the deck as live markup.
        is ContentElement.MathElement ->
            normalizeMathText(sanitizeMathml(repairStoredMathml(element.mathml)))
        is ContentElement.ImageElement -> imageElement(element.src)
        is ContentElement.TableElement -> tableToHtml(element)
        // The readable field carries the structure's text handles. Only a
        // valid PNG diagram is spooled through the media path, so it lands in
        // the deck as mcqapp-N.png and Anki resolves it by filename. The full
        // ChemElement itself rides unchanged in the third (mcqapp) payload.
        is ContentElement.ChemElement ->
            escapeHtml(chemElementText(element)) +
                if (isPngDataUri(element.diagram)) imageElement(element.diagram) else ""
    }

    private fun tableToHtml(table: ContentElement.TableElement): String {
        val sb = StringBuilder("<table border=\"1\">")
        table.rows.forEach { row ->
            sb.append("<tr>")
            row.forEach { cell ->
                sb.append("<td>").append(cellToHtml(cell)).append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    /**
     * One cell in Anki's field HTML, built from the shared cell grammar rather
     * than escaped as a whole:
     *
     * - a chemical structure becomes its readable handles plus its diagram,
     *   which is spooled as `mcqapp-N.png` (a structure without a valid PNG
     *   keeps the handles only, exactly as a standalone block does);
     * - a formula is normalized with the same [normalizeMathText] a top-level
     *   [ContentElement.MathElement] gets, so the deck shows `<math>` instead
     *   of escaped MathML;
     * - a portable cell picture is spooled through the media pool and
     *   referenced by filename, never written as its data URI text;
     * - everything else goes through [renderInlineHtml], so inline formatting
     *   survives and unknown markup stays visible.
     */
    private fun cellToHtml(cell: String): String =
        cellSegments(cell).joinToString("") { segment ->
            when (segment) {
                is CellSegment.Chem ->
                    escapeHtml(chemElementText(segment.element)) +
                        if (isPngDataUri(segment.element.diagram)) imageElement(segment.element.diagram) else ""
                is CellSegment.Text -> cellTextToHtml(segment.html)
            }
        }

    /** Prose and formulas of one cell, each through its own existing pipeline. */
    private fun cellTextToHtml(html: String): String =
        tableCellParts(html).joinToString("") { part ->
            when (part) {
                is CellPart.Math -> normalizeMathText(
                    sanitizeMathml(
                        mapCellImages(part.mathml) { src -> cellImageToHtml(src) },
                        // The map above already reduced every picture to the
                        // canonical, validated `<img>` this pool writes; it
                        // must survive the MathML sanitizer, while every tag
                        // the stored cell carried loses its attributes (a
                        // foreign `<mi onclick=…>` must not reach the deck).
                        passthroughTags = setOf("img")
                    )
                )
                is CellPart.Text -> cellProseToHtml(part.html)
            }
        }

    /**
     * One prose run of a cell: the inline pipeline around each picture, with a
     * picture spooled into the media pool. A torn `<img` tag is the shared
     * cell scanner's `null` source and collapses to the visible marker, so a
     * truncated payload can never survive as text.
     */
    private fun cellProseToHtml(html: String): String {
        var out: StringBuilder? = null
        var pos = 0
        forEachCellImage(html) { src, start, end ->
            val sb = out ?: StringBuilder(html.length).also { out = it }
            sb.append(renderInlineHtml(html.substring(pos, start)))
            sb.append(cellImageToHtml(src))
            pos = end
        }
        val sb = out ?: return renderInlineHtml(html)
        sb.append(renderInlineHtml(html.substring(pos)))
        return sb.toString()
    }

    /**
     * A picture from a cell as an Anki reference: only a portable inline
     * picture (or a remote URL Anki can fetch itself) is emitted. Anything
     * else — a torn tag, `file:`, `javascript:`, SVG, a code-injected
     * attribute — is not drawable and becomes the visible marker.
     */
    private fun cellImageToHtml(src: String?): String = when {
        src == null -> CELL_IMAGE_MARKER
        portableCellImageSrc(src) || isRemoteImage(src) -> imageElement(src).trimStart()
        else -> CELL_IMAGE_MARKER
    }

    /** A URL Anki itself fetches; unlike a data URI it needs no media entry. */
    private fun isRemoteImage(src: String): Boolean {
        val value = src.trim()
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("//")
    }

    private fun imageElement(src: String?): String {
        if (src.isNullOrBlank()) return ""
        val dataUri = parseDataUri(src)
        if (dataUri != null) {
            val filename = assigned.getOrPut(mediaKey(src)) {
                val name = safeName(spool.size, imageExtension(dataUri.mimeType))
                mediaMap[spool.size.toString()] = name
                spool.add(spool.size.toString(), dataUri.bytes)
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
