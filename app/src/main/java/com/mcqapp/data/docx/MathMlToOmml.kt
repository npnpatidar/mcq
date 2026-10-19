package com.mcqapp.data.docx

/**
 * MathML to Office Math (OMML, `m:oMath`), the mirror of [ommlToMathMl],
 * for writing `.docx` files the app can read back. The same subset is
 * understood in both directions: text runs, superscript/subscript,
 * fractions, square roots — and roots with a visible degree, which the
 * reader refuses but Word renders natively, so exporting one is still
 * the faithful choice (re-import linearises it with a warning).
 *
 * Anything else returns null so the caller falls back to linear text
 * plus a warning, matching the import side's policy of never silently
 * mangling a formula.
 */

internal sealed interface MathNode {
    data class El(val name: String, val children: MutableList<MathNode> = mutableListOf()) : MathNode
    data class Txt(val text: String) : MathNode
}

// The attribute group is lazy so it cannot swallow the self-closing slash:
// `<mspace width="0.5em"/>` is one tag, not an open tag whose attributes
// happen to end in "/".
private val mathTag = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)(\\s[^<>]*?)?(/?)>")

/** Elements that hold no content however a producer writes them. */
private val voidElements = setOf("mspace")

/** Elements whose character data is content, not layout. */
private val tokenElements = setOf("mi", "mn", "mo", "mtext", "ms")

/**
 * Parses a MathML string (bare, attribute-free tags, as the sanitizer
 * emits) into a forest. Whitespace-only text between tags is layout and
 * is dropped; character data inside token elements is kept verbatim.
 */
internal fun parseMathTree(mathml: String): List<MathNode> {
    val root = mutableListOf<MathNode>()
    val stack = ArrayDeque<MathNode.El>()
    var pos = 0
    fun attach(node: MathNode) {
        (stack.lastOrNull()?.children ?: root).add(node)
    }
    fun attachText(text: String) {
        if (text.isBlank() && stack.lastOrNull()?.name !in tokenElements) return
        attach(MathNode.Txt(text))
    }
    while (pos < mathml.length) {
        val match = mathTag.find(mathml, pos)
        if (match == null) {
            attachText(mathml.substring(pos))
            break
        }
        if (match.range.first > pos) {
            attachText(mathml.substring(pos, match.range.first))
        }
        val closing = match.groupValues[1] == "/"
        val selfClosing = match.groupValues[4] == "/"
        val name = match.groupValues[2]
        when {
            closing -> {
                // A stray closing tag pops nothing it does not own, so it
                // cannot swallow unrelated content.
                val idx = stack.indexOfLast { it.name == name }
                if (idx >= 0) while (stack.size > idx) stack.removeLast()
            }
            // A space element is empty by definition. The sanitizer drops its
            // self-closing slash along with its attributes, so a `<mspace>`
            // reaches us looking like a container; opening one would make
            // every following sibling its child, and convertNode("mspace")
            // drops children — silently losing the rest of the formula.
            selfClosing || name in voidElements -> attach(MathNode.El(name))
            else -> {
                val el = MathNode.El(name)
                attach(el)
                stack.addLast(el)
            }
        }
        pos = match.range.last + 1
    }
    return root
}

/** Concatenated character data of a subtree (entities already decoded). */
private fun MathNode.flattenText(): String = when (this) {
    is MathNode.Txt -> text
    is MathNode.El -> children.joinToString("") { it.flattenText() }
}

private fun MathNode.elementChildren(): List<MathNode.El> =
    (this as? MathNode.El)?.children?.filterIsInstance<MathNode.El>() ?: emptyList()

/**
 * Decodes the character references MathML legitimately carries
 (`&#x2212;`, `&prime;`, …) to the characters Word should show.
 Unknown named references stay literal rather than vanishing.
 */
internal fun decodeXmlEntities(s: String): String {
    if (!s.contains('&')) return s
    val pattern = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    return pattern.replace(s) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#") -> {
                val code = if (body[1] == 'x' || body[1] == 'X') {
                    body.substring(2).toIntOrNull(16)
                } else {
                    body.substring(1).toIntOrNull()
                }
                if (code != null && code in 0..0x10FFFF) {
                    String(Character.toChars(code))
                } else {
                    match.value
                }
            }
            else -> xmlNamedEntities[body] ?: match.value
        }
    }
}

internal val xmlNamedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "prime" to "′", "Prime" to "″",
    "minus" to "−", "times" to "×", "divide" to "÷", "plusmn" to "±",
    "deg" to "°", "middot" to "·", "sdot" to "⋅", "lowast" to "∗",
    "infin" to "∞", "infty" to "∞", "hellip" to "…", "mdash" to "—",
    "ndash" to "–", "larr" to "←", "rarr" to "→", "harr" to "↔",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠",
    "neq" to "≠", "sim" to "∼", "asymp" to "≈", "approx" to "≈",
    "prop" to "∝", "propto" to "∝", "radic" to "√", "sum" to "∑",
    "prod" to "∏", "int" to "∫", "part" to "∂", "nabla" to "∇",
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "phi" to "φ", "omega" to "ω",
    "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ",
    "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ",
    "Phi" to "Φ", "Omega" to "Ω", "equiv" to "≡", "perp" to "⊥",
    "angle" to "∠", "there4" to "∴"
)

internal fun escapeXmlText(s: String): String = buildString(s.length + 16) {
    for (c in s) when (c) {
        '&' -> append("\u0026amp;")
        '<' -> append("\u0026lt;")
        '>' -> append("\u0026gt;")
        else -> append(c)
    }
}

/** One OMML text run: `<m:r><m:t>…</m:t></m:r>`. */
private fun mathRun(text: String): String =
    "<m:r><m:t xml:space=\"preserve\">${escapeXmlText(text)}</m:t></m:r>"

private fun MathNode.isEl(vararg names: String) =
    this is MathNode.El && name in names

/**
 * The inner content of an `m:oMath` for [mathml], or null when it uses a
 * construct outside the subset. Blank input is also null: an empty
 * equation carries nothing worth writing.
 */
internal fun mathMlToOmml(mathml: String): String? {
    if (mathml.isBlank()) return null
    val decoded = decodeXmlEntities(mathml)
    val forest = parseMathTree(decoded)
    // The sanitized form always has a <math> root; be lenient about a
    // fragment anyway (round-tripped files, hand-built tests).
    val top = if (forest.size == 1 && forest[0].isEl("math")) {
        (forest[0] as MathNode.El).children
    } else {
        forest
    }
    val inner = convertAll(top) ?: return null
    return inner.ifBlank { null }
}

private fun convertAll(nodes: List<MathNode>): String? {
    val sb = StringBuilder()
    for (node in nodes) {
        val part = convertNode(node) ?: return null
        sb.append(part)
    }
    return sb.toString()
}

private fun convertNode(node: MathNode): String? {
    return when (node) {
    is MathNode.Txt -> if (node.text.isBlank()) "" else mathRun(node.text)
    is MathNode.El -> when (node.name) {
        "mi", "mn", "mo", "mtext", "ms" -> mathRun(node.flattenText())
        "mspace" -> mathRun(" ")
        // Structural wrapper: transparent. `math` is one too, for the
        // lenient fragment path below, where the forest is not a lone root.
        "mrow", "mpadded", "mphantom", "mstyle", "math" -> convertAll(node.children)
        "msup", "msub" -> {
            val parts = node.elementChildren()
            if (parts.size != 2) return null
            val base = convertAll(listOf(parts[0])) ?: return null
            val script = convertAll(listOf(parts[1])) ?: return null
            if (node.name == "msup") {
                "<m:sSup><m:e>$base</m:e><m:sup>$script</m:sup></m:sSup>"
            } else {
                "<m:sSub><m:e>$base</m:e><m:sub>$script</m:sub></m:sSub>"
            }
        }
        "mfrac" -> {
            val parts = node.elementChildren()
            if (parts.size != 2) return null
            val num = convertAll(listOf(parts[0])) ?: return null
            val den = convertAll(listOf(parts[1])) ?: return null
            "<m:f><m:num>$num</m:num><m:den>$den</m:den></m:f>"
        }
        "msqrt" -> {
            val body = convertAll(node.children) ?: return null
            "<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr>" +
                "<m:deg/><m:e>$body</m:e></m:rad>"
        }
        "mroot" -> {
            val parts = node.elementChildren()
            if (parts.size != 2) return null
            val body = convertAll(listOf(parts[0])) ?: return null
            val degree = convertAll(listOf(parts[1])) ?: return null
            "<m:rad><m:deg>$degree</m:deg><m:e>$body</m:e></m:rad>"
        }
        else -> null
    }
    }
}

/** Best-effort linear text of a MathML the converter refused. */
internal fun mathMlLinearText(mathml: String): String =
    decodeXmlEntities(parseMathTree(mathml).joinToString("") { it.flattenText() })
        .replace(Regex("\\s+"), " ").trim()
