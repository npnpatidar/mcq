package com.mcqapp.data.docx

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
internal const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val DRAWING_MAIN_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

/** Blocks of the document body in order: text lines or tables. */
internal sealed interface DocxBlock {
    data class Para(val html: String) : DocxBlock
    data class Table(val rows: List<List<String>>) : DocxBlock
}

internal class DocxRead(
    val blocks: List<DocxBlock>,
    val warnings: MutableList<String> = mutableListOf()
)

/**
 * Reads a .docx question bank into paragraph/table blocks with inline
 * HTML (`<strong>`, `<sub>`, `<math>`, `<img src="data:…">`, …).
 *
 * Only what the marker format uses is understood: paragraphs, runs with
 * bold/italic/underline/strike/sub/sup/highlight, line breaks, tables,
 * embedded images and OMML equations. Automatic numbering (`w:numPr`)
 * is ignored — question/option numbers must be literal text, which is
 * what the marker parser splits on.
 */
internal fun readDocx(bytes: ByteArray): DocxRead {
    val warnings = mutableListOf<String>()
    val entries = unzip(bytes)
    val docXml = entries["word/document.xml"]
        ?: throw IllegalArgumentException("Not a Word document: word/document.xml is missing.")
    val doc = parseXml(docXml, "word/document.xml")
    val rels = parseRels(entries["word/_rels/document.xml.rels"])
    val mimes = parseContentTypes(entries["[Content_Types].xml"])
    val reader = BodyReader(entries, rels, mimes, warnings)
    val body = doc.documentElement.childElements().firstOrNull { it.isWord("body") }
        ?: throw IllegalArgumentException("Not a Word document: w:body is missing.")
    val blocks = mutableListOf<DocxBlock>()
    for (child in body.childElements()) {
        when {
            child.isWord("p") -> reader.paraHtml(child)?.let { blocks.add(DocxBlock.Para(it)) }
            child.isWord("tbl") -> blocks.add(DocxBlock.Table(reader.tableRows(child)))
            child.isWord("sectPr") -> Unit
            else -> warnings.add(
                "Ignoring an unsupported top-level element <${child.tagName}> — " +
                    "only paragraphs and tables are read."
            )
        }
    }
    return DocxRead(blocks, warnings)
}

private fun Element.isWord(local: String) = namespaceURI == WORD_NS && localName == local

private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buf = ByteArrayOutputStream()
                    zip.copyTo(buf)
                    out[entry.name] = buf.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Not a readable .docx (zip) file: ${e.message}")
    }
    if (out.isEmpty()) throw IllegalArgumentException("Not a readable .docx (zip) file: empty archive.")
    return out
}

internal fun parseXml(bytes: ByteArray, what: String): Document {
    return try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Older parsers: Word files never carry a doctype anyway.
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Exception) {
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("Not a readable Word part ($what): ${e.message}")
    }
}

private fun parseRels(xml: ByteArray?): Map<String, String> {
    if (xml == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (rel in parseXml(xml, "document.xml.rels").documentElement.childElements()) {
        val id = rel.getAttribute("Id")
        val target = rel.getAttribute("Target")
        if (id.isNotBlank() && target.isNotBlank()) out[id] = target
    }
    return out
}

private fun parseContentTypes(xml: ByteArray?): Map<String, String> {
    if (xml == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in parseXml(xml, "[Content_Types].xml").documentElement.childElements()) {
        val name = part.getAttribute("PartName")
        val type = part.getAttribute("ContentType")
        if (name.isNotBlank() && type.isNotBlank()) out[name] = type
    }
    return out
}

private class BodyReader(
    private val entries: Map<String, ByteArray>,
    private val rels: Map<String, String>,
    private val contentTypes: Map<String, String>,
    private val warnings: MutableList<String>
) {
    /** Paragraph to an HTML line, or null when it carries no content. */
    fun paraHtml(para: Element): String? {
        val sb = StringBuilder()
        var hasContent = false
        for (child in para.childElements()) {
            when {
                child.isWord("r") -> {
                    val (html, content) = runHtml(child, inDel = false)
                    sb.append(html)
                    if (content) hasContent = true
                }
                child.isWord("del") -> {
                    val inner = StringBuilder()
                    var delContent = false
                    for (run in child.childElements()) {
                        if (!run.isWord("r")) continue
                        val (html, content) = runHtml(run, inDel = true)
                        inner.append(html)
                        if (content) delContent = true
                    }
                    if (delContent) {
                        sb.append("<del>").append(inner).append("</del>")
                        hasContent = true
                    }
                }
                child.isWord("hyperlink") -> {
                    for (run in child.childElements()) {
                        if (!run.isWord("r")) continue
                        val (html, content) = runHtml(run, inDel = false)
                        sb.append(html)
                        if (content) hasContent = true
                    }
                }
                child.isWord("pPr") || child.isWord("bookmarkStart") ||
                    child.isWord("bookmarkEnd") || child.isWord("proofErr") -> Unit
                child.namespaceURI == MATH_NS -> {
                    // A display equation on its own line.
                    val math = ommlToMathMl(child, warnings, "equation")
                    if (math != null) {
                        sb.append(math)
                        hasContent = true
                    } else {
                        val linear = ommlLinearText(child)
                        if (linear.isNotBlank()) {
                            sb.append(linear)
                            hasContent = true
                        }
                        warnings.add(
                            "An equation uses unsupported constructs and was kept as plain text — " +
                                "check its question after import."
                        )
                    }
                }
                else -> {
                    // w:fldChar, w:instrText and the like carry no content.
                }
            }
        }
        if (!hasContent) return null
        // Lines never end with stray spaces (pandoc output doesn't either);
        // leading space is kept as authored.
        return sb.toString().trimEnd()
    }

    /**
     * A run to HTML plus whether it contributes visible content (an image
     * or equation counts even though its text is empty).
     */
    private fun runHtml(run: Element, inDel: Boolean): Pair<String, Boolean> {
        val sb = StringBuilder()
        var text = StringBuilder()
        // NOTE: raw text is intentionally NOT entity-escaped (bare & and <
        // stay as-is), mirroring the Python pipeline: our importer and
        // display layer both handle raw symbols, and escaping here would
        // diverge every text hash from pipeline-produced files.
        var content = false
        fun flushText() {
            if (text.isNotEmpty()) {
                sb.append(text.toString())
                content = true
                text = StringBuilder()
            }
        }
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var sub = false
        var sup = false
        var mark = false
        for (child in run.childElements()) {
            when {
                child.isWord("rPr") -> {
                    for (prop in child.childElements()) {
                        if (!prop.isWord(prop.localName)) continue
                        when (prop.localName) {
                            "b", "bCs" -> if (isOn(prop)) bold = true
                            "i", "iCs" -> if (isOn(prop)) italic = true
                            "u" -> if (prop.getAttribute("w:val").isBlank() ||
                                prop.getAttribute("w:val") != "none"
                            ) {
                                underline = true
                            }
                            "strike", "dstrike" -> if (isOn(prop)) strike = true
                            "vertAlign" -> when (prop.getAttribute("w:val")) {
                                "subscript" -> sub = true
                                "superscript" -> sup = true
                            }
                            "highlight" -> if (prop.getAttribute("w:val").isNotBlank() &&
                                prop.getAttribute("w:val") != "none"
                            ) {
                                mark = true
                            }
                        }
                    }
                }
                child.isWord("t") -> text.append(child.textContent)
                child.isWord("tab") -> text.append(" ")
                child.isWord("br") || child.isWord("cr") -> text.append("\n")
                child.isWord("noBreakHyphen") -> text.append("-")
                child.isWord("sym") -> {
                    child.getAttribute("w:char").toIntOrNull(16)?.let { code ->
                        if (code in 0..0x10FFFF) {
                            text.append(String(Character.toChars(code)))
                        }
                    }
                }
                child.isWord("delText") -> text.append(child.textContent)
                child.isWord("drawing") || child.isWord("pict") -> {
                    flushText()
                    imageTag(child)?.let {
                        sb.append(it)
                        content = true
                    }
                }
                child.namespaceURI == MATH_NS && child.localName == "oMath" -> {
                    flushText()
                    val math = ommlToMathMl(child, warnings, "equation")
                    if (math != null) {
                        sb.append(math)
                    } else {
                        val linear = ommlLinearText(child)
                        if (linear.isNotBlank()) sb.append(linear)
                        warnings.add(
                            "An equation uses unsupported constructs and was kept as plain text — " +
                                "check its question after import."
                        )
                    }
                    content = true
                }
                // w:fldChar, w:instrText, w:rPr handled above, bookmarks: no content.
            }
        }
        flushText()
        var html = sb.toString()
        // Fixed nesting order so output is deterministic.
        if (sub) html = "<sub>$html</sub>"
        if (sup) html = "<sup>$html</sup>"
        if (bold) html = "<strong>$html</strong>"
        if (italic) html = "<em>$html</em>"
        if (underline) html = "<u>$html</u>"
        if (strike) html = "<del>$html</del>"
        if (mark) html = "<mark>$html</mark>"
        if (inDel && html.isNotEmpty()) html = "<del>$html</del>"
        return html to content
    }

    private fun isOn(prop: Element): Boolean {
        val v = prop.getAttribute("w:val")
        return v.isBlank() || (v != "0" && v != "false" && v != "off" && v != "none")
    }

    /** `<img src="data:…">` for an embedded drawing, or null with a warning. */
    private fun imageTag(drawing: Element): String? {
        // w:drawing/wp:inline/a:graphic/a:graphicData/pic:pic/
        // pic:blipFill/a:blip, or VML w:pict/v:shape/v:imagedata.
        val blip = descendants(drawing).firstOrNull {
            (it.localName == "blip" || it.localName == "imagedata") &&
                (it.namespaceURI == DRAWING_MAIN_NS || it.namespaceURI == WORD_NS ||
                    it.namespaceURI?.endsWith("/vml") == true || it.namespaceURI == null)
        }
        val ref = blip?.let {
            val q = it.getAttribute("r:embed")
            if (q.isNotBlank()) q else it.getAttributeNS(REL_NS, "embed")
        }
        if (ref.isNullOrBlank()) {
            warnings.add("A picture has no readable image reference and was skipped.")
            return null
        }
        val bytes = resolveEntry(rels[ref] ?: ref) ?: run {
            warnings.add("A picture points at a missing file ($ref) and was skipped.")
            return null
        }
        val mime = mimeFor(rels[ref] ?: ref)
        return "<img src=\"data:$mime;base64," +
            java.util.Base64.getEncoder().encodeToString(bytes) + "\">"
    }

    private fun descendants(el: Element): Sequence<Element> = sequence {
        for (child in el.childElements()) {
            yield(child)
            yieldAll(descendants(child))
        }
    }

    private fun resolveEntry(target: String): ByteArray? {
        val norm = target.trimStart('/')
        return entries[norm] ?: entries["word/$norm"]
    }

    private fun mimeFor(target: String): String {
        val norm = target.trimStart('/')
        contentTypes["/$norm"] ?: contentTypes["/word/$norm"]?.let { return it }
        contentTypes[norm] ?: contentTypes["word/$norm"]?.let { return it }
        val ext = norm.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "tif", "tiff" -> "image/tiff"
            "svg" -> "image/svg+xml"
            "emf" -> "image/x-emf"
            "wmf" -> "image/x-wmf"
            else -> "application/octet-stream"
        }
    }

    fun tableRows(table: Element): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (child in table.childElements()) {
            if (!child.isWord("tr")) continue
            val cells = mutableListOf<String>()
            for (cell in child.childElements()) {
                if (!cell.isWord("tc")) continue
                val parts = mutableListOf<String>()
                for (para in cell.childElements()) {
                    when {
                        para.isWord("p") -> paraHtml(para)?.let { parts.add(it) }
                        para.isWord("tbl") -> {
                            // Nested tables cannot travel in a cell string:
                            // flatten with spaces.
                            for (nested in tableRows(para)) parts.add(nested.joinToString(" "))
                        }
                    }
                }
                cells.add(parts.joinToString(" "))
            }
            rows.add(cells)
        }
        return rows
    }
}

/** Best-effort linear text of an unsupported equation. */
internal fun ommlLinearText(oMath: Element): String {    val sb = StringBuilder()
    fun walk(el: Element) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeType) {
                org.w3c.dom.Node.TEXT_NODE -> sb.append(node.nodeValue)
                org.w3c.dom.Node.ELEMENT_NODE -> walk(node as Element)
            }
        }
    }
    walk(oMath)
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}
