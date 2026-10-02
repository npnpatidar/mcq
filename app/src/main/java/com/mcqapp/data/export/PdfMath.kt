package com.mcqapp.data.export

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.style.ReplacementSpan

/**
 * Real formulas on PDF canvas: a MathML subset (text, fractions, roots,
 * scripts) measured and drawn with [TextPaint], flowing inline via
 * [MathSpan]. Anything outside the subset makes [mathMlToNodes] return
 * null and the caller keeps the `[formula: …]` linear placeholder.
 * Offline, dependency-free, synchronous.
 */
internal sealed interface TexNode {
    data class Run(val text: String) : TexNode
    data class Frac(val num: List<TexNode>, val den: List<TexNode>) : TexNode
    data class Sqrt(val body: List<TexNode>) : TexNode
    data class Sup(val base: List<TexNode>, val exp: List<TexNode>) : TexNode
    data class Sub(val base: List<TexNode>, val sub: List<TexNode>) : TexNode
}

private val mathTagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*?/?>")

private sealed interface Tok {
    data class Open(val name: String, val selfClosing: Boolean) : Tok
    data class Close(val name: String) : Tok
    data class Text(val text: String) : Tok
}

private fun tokenize(input: String): List<Tok> {
    val out = mutableListOf<Tok>()
    var pos = 0
    for (match in mathTagPattern.findAll(input)) {
        if (match.range.first > pos) out.add(Tok.Text(input.substring(pos, match.range.first)))
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2]
        val selfClosing = !closing && match.value.endsWith("/>")
        out.add(if (closing) Tok.Close(name) else Tok.Open(name, selfClosing))
        pos = match.range.last + 1
    }
    if (pos < input.length) out.add(Tok.Text(input.substring(pos)))
    return out
}

private val leafTags = setOf("mi", "mn", "mo", "mtext")
private val entityPattern = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

internal fun decodeEntities(s: String): String {
    if (!s.contains('&')) return s
    return entityPattern.replace(s) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#") -> {
                val code = if (body[1] == 'x' || body[1] == 'X') body.substring(2).toIntOrNull(16)
                else body.substring(1).toIntOrNull()
                if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else match.value
            }
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" -> "'"
            else -> match.value
        }
    }
}

private class Cursor(val toks: List<Tok>) {
    var index = 0
    fun peek(): Tok? = toks.getOrNull(index)
}

/**
 * Parses the MathML subset; null when anything outside it appears
 * (tables, multiscripts, indexed roots, unknown elements, malformed
 * markup). Blank text between elements is layout, not content.
 */
internal fun mathMlToNodes(mathml: String): List<TexNode>? {
    val cur = Cursor(tokenize(mathml))
    // Skip to the outer <math>.
    while (true) {
        when (val tok = cur.peek()) {
            null -> return null
            is Tok.Open -> if (tok.name == "math") break else cur.index++
            else -> cur.index++
        }
    }
    cur.index++ // consume <math>
    val nodes = parseSiblings(cur, "math") ?: return null
    return nodes.ifEmpty { null }
}

/** Adds a parsed element spliced: groups flatten, skips vanish. Null fails. */
private fun MutableList<TexNode>.addParsed(node: TexNode?): Boolean {
    when (node) {
        null -> return false
        is GroupNode -> addAll(node.kids)
        is SkippedNode -> Unit
        else -> add(node)
    }
    return true
}

/** Parses siblings until `</stopTag>`; null on mismatch or foreign markup. */
private fun parseSiblings(cur: Cursor, stopTag: String): MutableList<TexNode>? {
    val out = mutableListOf<TexNode>()
    while (true) {
        when (val tok = cur.peek()) {
            null -> return null
            is Tok.Close -> {
                cur.index++
                return if (tok.name == stopTag) out else null
            }
            is Tok.Text -> {
                cur.index++
                if (tok.text.isNotBlank()) out.add(TexNode.Run(collapse(tok.text)))
            }
            is Tok.Open -> {
                if (tok.name == stopTag) {
                    // A stray reopen of the stop tag is malformed.
                    return null
                }
                if (!out.addParsed(parseElement(cur, tok))) return null
            }
        }
    }
}

/** Parses one element; the opening tag is current. Groups splice, skips vanish. */
private fun parseElement(cur: Cursor, open: Tok.Open): TexNode? {
    cur.index++ // consume opening tag
    // An empty element contributes nothing: splice away, never fail here.
    if (open.selfClosing) return SkippedNode
    return when (open.name) {
        "mrow", "math", "semantics" -> {
            // <semantics> carries presentation plus TeX annotations;
            // annotations become SkippedNode and vanish via addParsed.
            GroupNode(parseSiblings(cur, open.name) ?: return null)
        }
        "mi", "mn", "mo", "mtext" -> readLeaf(cur, open.name) ?: return null
        "annotation", "annotation-xml" -> {
            skipSubtree(cur, open.name)
            SkippedNode
        }
        "msup", "msub", "mfrac" -> {
            val kids = parseChildNodes(cur, open.name, expected = 2) ?: return null
            when (open.name) {
                "msup" -> TexNode.Sup(kids[0], kids[1])
                "msub" -> TexNode.Sub(kids[0], kids[1])
                else -> TexNode.Frac(kids[0], kids[1])
            }
        }
        "msqrt" -> {
            val kids = parseChildNodes(cur, open.name, expected = -1) ?: return null
            if (kids.isEmpty()) return null
            TexNode.Sqrt(kids.flatten())
        }
        else -> null
    }
}

/** Reads leaf character data until the matching close tag. */
private fun readLeaf(cur: Cursor, name: String): TexNode? {
    val text = StringBuilder()
    while (true) {
        when (val tok = cur.peek()) {
            null -> return null
            is Tok.Close -> {
                cur.index++
                if (tok.name != name) return null
                val decoded = decodeEntities(text.toString())
                return if (decoded.isEmpty()) SkippedNode else TexNode.Run(decoded)
            }
            is Tok.Open -> return null // no nesting inside leaves
            is Tok.Text -> {
                cur.index++
                text.append(tok.text)
            }
        }
    }
}

/** Parses exactly [expected] child nodes (each a one-node group), or any count when negative. */
private fun parseChildNodes(cur: Cursor, parent: String, expected: Int): List<List<TexNode>>? {
    val groups = mutableListOf<List<TexNode>>()
    while (true) {
        when (val tok = cur.peek()) {
            null -> return null
            is Tok.Close -> {
                cur.index++
                if (tok.name != parent) return null
                if (expected >= 0 && groups.size != expected) return null
                return groups
            }
            is Tok.Text -> {
                cur.index++
                if (tok.text.isNotBlank()) groups.add(listOf(TexNode.Run(collapse(tok.text))))
            }
            is Tok.Open -> {
                if (tok.name == "annotation" || tok.name == "annotation-xml") {
                    cur.index++
                    if (!tok.selfClosing) skipSubtree(cur, tok.name)
                    continue
                }
                val node = parseElement(cur, tok) ?: return null
                if (node is SkippedNode) continue
                groups.add(if (node is GroupNode) node.kids else listOf(node))
            }
        }
    }
}

/** Consumes tokens through the matching close tag, nesting-aware. */
private fun skipSubtree(cur: Cursor, name: String) {
    var depth = 1
    while (depth > 0) {
        when (val tok = cur.peek()) {
            null -> return
            is Tok.Open -> {
                cur.index++
                if (tok.name == name && !tok.selfClosing) depth++
            }
            is Tok.Close -> {
                cur.index++
                if (tok.name == name) depth--
            }
            is Tok.Text -> cur.index++
        }
    }
}

/** Transparent group (mrow/semantics); never escapes into the tree. */
private data class GroupNode(val kids: List<TexNode>) : TexNode

/** Placeholder filtered out by parents; never escapes into the tree. */
private object SkippedNode : TexNode {
    override fun toString(): String = "Skipped"
}

private fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

internal data class Measured(val width: Float, val ascent: Float, val descent: Float)

private fun metrics(paint: TextPaint): Pair<Float, Float> {
    val fm = paint.fontMetrics
    return -fm.ascent to fm.descent
}

internal fun measureNodes(nodes: List<TexNode>, paint: TextPaint): Measured {
    var width = 0f
    var ascent = 0f
    var descent = 0f
    for (node in nodes) {
        val m = measureNode(node, paint)
        width += m.width
        ascent = maxOf(ascent, m.ascent)
        descent = maxOf(descent, m.descent)
    }
    return Measured(width, ascent, descent)
}

private fun scaled(paint: TextPaint, factor: Float): TextPaint =
    TextPaint(paint).apply { textSize = paint.textSize * factor }

private fun measureNode(node: TexNode, paint: TextPaint): Measured {
    return when (node) {
        is TexNode.Run -> {
            val w = if (node.text.isEmpty()) 0f else paint.measureText(node.text)
            val (a, d) = metrics(paint)
            Measured(w, a, d)
        }
        is TexNode.Sup, is TexNode.Sub -> {
            val (base, script, up) = when (node) {
                is TexNode.Sup -> Triple(node.base, node.exp, true)
                is TexNode.Sub -> Triple(node.base, (node as TexNode.Sub).sub, false)
                else -> error("unreachable")
            }
            val small = scaled(paint, 0.7f)
            val b = measureNodes(base, paint)
            val s = measureNodes(script, small)
            val shift = b.ascent * 0.45f
            val ascent = if (up) maxOf(b.ascent, shift + s.ascent) else b.ascent
            val descent = if (up) maxOf(b.descent, s.descent - shift) else maxOf(b.descent, shift + s.descent)
            Measured(b.width + s.width, ascent, descent)
        }
        is TexNode.Frac -> {
            val n = measureNodes(node.num, paint)
            val d = measureNodes(node.den, paint)
            val gap = paint.textSize * 0.12f
            Measured(
                maxOf(n.width, d.width) + paint.textSize * 0.2f,
                n.ascent + n.descent + gap,
                d.ascent + d.descent + gap
            )
        }
        is TexNode.Sqrt -> {
            val body = measureNodes(node.body, paint)
            val rootW = paint.measureText("√")
            val gap = paint.textSize * 0.08f
            Measured(rootW + body.width + gap, body.ascent + gap, body.descent)
        }
        is GroupNode -> measureNodes(node.kids, paint)
        is SkippedNode -> Measured(0f, 0f, 0f)
    }
}

internal fun drawNodes(canvas: Canvas, nodes: List<TexNode>, x: Float, baselineY: Float, paint: TextPaint) {
    var cx = x
    for (node in nodes) {
        cx += drawNode(canvas, node, cx, baselineY, paint)
    }
}

private fun drawNode(canvas: Canvas, node: TexNode, x: Float, baselineY: Float, paint: TextPaint): Float {
    return when (node) {
        is TexNode.Run -> {
            if (node.text.isNotEmpty()) canvas.drawText(node.text, x, baselineY, paint)
            paint.measureText(node.text)
        }
        is TexNode.Sup, is TexNode.Sub -> {
            val (base, script, up) = when (node) {
                is TexNode.Sup -> Triple(node.base, node.exp, true)
                is TexNode.Sub -> Triple(node.base, (node as TexNode.Sub).sub, false)
                else -> error("unreachable")
            }
            val small = scaled(paint, 0.7f)
            val b = measureNodes(base, paint)
            drawNodes(canvas, base, x, baselineY, paint)
            val shift = b.ascent * 0.45f
            val scriptY = if (up) baselineY - shift else baselineY + shift
            drawNodes(canvas, script, x + b.width, scriptY, small)
            val s = measureNodes(script, small)
            b.width + s.width
        }
        is TexNode.Frac -> {
            val n = measureNodes(node.num, paint)
            val d = measureNodes(node.den, paint)
            val gap = paint.textSize * 0.12f
            val w = maxOf(n.width, d.width) + paint.textSize * 0.2f
            val ruleY = baselineY - d.ascent - d.descent - gap
            drawNodes(canvas, node.num, x + (w - n.width) / 2, ruleY - gap - n.descent, paint)
            drawNodes(canvas, node.den, x + (w - d.width) / 2, ruleY + gap + d.ascent, paint)
            val oldStyle = paint.style
            val oldWidth = paint.strokeWidth
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, paint.textSize * 0.04f)
            canvas.drawLine(x, ruleY, x + w, ruleY, paint)
            paint.style = oldStyle
            paint.strokeWidth = oldWidth
            w
        }
        is TexNode.Sqrt -> {
            val body = measureNodes(node.body, paint)
            val rootW = paint.measureText("√")
            val gap = paint.textSize * 0.08f
            canvas.drawText("√", x, baselineY, paint)
            drawNodes(canvas, node.body, x + rootW, baselineY, paint)
            val topY = baselineY - body.ascent - gap
            val oldStyle = paint.style
            val oldWidth = paint.strokeWidth
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, paint.textSize * 0.05f)
            canvas.drawLine(x + rootW, topY, x + rootW + body.width + gap, topY, paint)
            paint.style = oldStyle
            paint.strokeWidth = oldWidth
            rootW + body.width + gap
        }
        is GroupNode -> {
            var cx = 0f
            for (kid in node.kids) cx += drawNode(canvas, kid, x + cx, baselineY, paint)
            cx
        }
        is SkippedNode -> 0f
    }
}

/**
 * An inline formula inside a PDF paragraph: measures like text so
 * [android.text.StaticLayout] flows sentences around it.
 */
internal class MathSpan(private val nodes: List<TexNode>, private val sizePx: Float) : ReplacementSpan() {
    private fun sized(base: Paint): TextPaint =
        TextPaint(base as? TextPaint ?: TextPaint()).apply { textSize = sizePx }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val p = sized(paint)
        val m = measureNodes(nodes, p)
        fm?.let {
            it.ascent = -m.ascent.toInt().coerceAtLeast(1)
            it.descent = m.descent.toInt().coerceAtLeast(0)
            it.top = it.ascent
            it.bottom = it.descent
        }
        return m.width.toInt().coerceAtLeast(1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        drawNodes(canvas, nodes, x, y.toFloat(), sized(paint))
    }
}
