package com.mcqapp.ui.editor

// TRIAL prototype: minimal MathML -> LaTeX subset converter.
// Pure Kotlin (no Android imports) so plain JUnit tests can cover it.
// Supported: mi/mn/mo/mtext/ms/text literal text, mrow-style flattening,
// msup/msub/msubsup, mfrac, msqrt, mroot. Everything else (unknown tags,
// malformed markup) degrades to best-effort text extraction, never throws.

private sealed interface MlNode {
    data class El(val name: String, val children: MutableList<MlNode> = mutableListOf()) : MlNode
    data class Tx(val text: String) : MlNode
}

private val ENTITY_REF = Regex("&(#x[0-9A-Fa-f]+|#\\d+|[A-Za-z]+);")

private fun codePointToString(code: Int): String = try {
    String(Character.toChars(code))
} catch (_: IllegalArgumentException) {
    ""
}

private fun unescapeEntities(s: String): String = ENTITY_REF.replace(s) { m ->
    val e = m.groupValues[1]
    when {
        e == "lt" -> "<"
        e == "gt" -> ">"
        e == "amp" -> "&"
        e == "quot" -> "\""
        e == "apos" -> "'"
        e == "nbsp" -> " "
        e.startsWith("#x", ignoreCase = true) ->
            e.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: m.value
        e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePointToString) ?: m.value
        else -> m.value
    }
}

private fun escapeLatex(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) {
        if (c == '\\' || c == '{' || c == '}' || c == '$' || c == '%' ||
            c == '&' || c == '_' || c == '#'
        ) {
            out.append('\\')
        }
        out.append(c)
    }
    return out.toString()
}

// Tiny tag scanner: attributes are ignored entirely (also drops xmlns,
// display and friends). Handles comments, PIs and mismatched tags.
private fun parseMathMl(s: String): MlNode.El {
    val root = MlNode.El("root")
    val stack = ArrayDeque<MlNode.El>()
    stack.addLast(root)
    var i = 0
    val n = s.length
    while (i < n) {
        val lt = s.indexOf('<', i)
        if (lt < 0) {
            val t = s.substring(i)
            if (t.isNotBlank()) stack.last().children.add(MlNode.Tx(t))
            break
        }
        if (lt > i) {
            val t = s.substring(i, lt)
            if (t.isNotBlank()) stack.last().children.add(MlNode.Tx(t))
        }
        if (s.startsWith("<!--", lt)) {
            val end = s.indexOf("-->", lt + 4)
            i = if (end < 0) n else end + 3
            continue
        }
        if (s.startsWith("<?", lt)) {
            val end = s.indexOf("?>", lt + 2)
            i = if (end < 0) n else end + 2
            continue
        }
        val gt = s.indexOf('>', lt + 1)
        if (gt < 0) {
            val t = s.substring(lt)
            if (t.isNotBlank()) stack.last().children.add(MlNode.Tx(t))
            break
        }
        var tag = s.substring(lt + 1, gt).trim()
        if (tag.startsWith("!")) {
            i = gt + 1
            continue
        }
        val closing = tag.startsWith("/")
        if (closing) tag = tag.drop(1).trim()
        val selfClosing = tag.endsWith("/")
        if (selfClosing) tag = tag.dropLast(1).trim()
        val name = tag.split(' ', '\t', '\n', '\r', '/').firstOrNull().orEmpty()
            .substringAfter(':').lowercase()
        if (name.isEmpty()) {
            i = gt + 1
            continue
        }
        if (closing) {
            val idx = stack.indexOfLast { it.name == name }
            if (idx > 0) {
                while (stack.size > idx + 1) stack.removeLast()
                stack.removeLast()
            }
        } else {
            val el = MlNode.El(name)
            stack.last().children.add(el)
            if (!selfClosing) stack.addLast(el)
        }
        i = gt + 1
    }
    return root
}

private fun renderKids(el: MlNode.El): String =
    el.children.joinToString("") { renderNode(it) }

private fun renderNode(node: MlNode): String = when (node) {
    is MlNode.Tx -> escapeLatex(unescapeEntities(node.text))
    is MlNode.El -> renderElement(node)
}

private fun renderElement(el: MlNode.El): String {
    val kids = el.children
    when (el.name) {
        "mi", "mn", "mo", "mtext", "ms", "text" -> return renderKids(el)
        "msup" -> if (kids.size >= 2) {
            return renderNode(kids[0]) + "^{" + renderNode(kids[1]) + "}"
        }
        "msub" -> if (kids.size >= 2) {
            return renderNode(kids[0]) + "_{" + renderNode(kids[1]) + "}"
        }
        "msubsup" -> if (kids.size >= 3) {
            return renderNode(kids[0]) + "_{" + renderNode(kids[1]) + "}^{" + renderNode(kids[2]) + "}"
        }
        "mfrac" -> {
            val num = if (kids.size > 0) renderNode(kids[0]) else ""
            val den = if (kids.size > 1) renderNode(kids[1]) else ""
            return "\\frac{$num}{$den}"
        }
        "msqrt" -> return "\\sqrt{" + renderKids(el) + "}"
        // LIMITATION: nth roots carry the index as an optional argument;
        // readers that ignore it still get the radicand right.
        "mroot" -> {
            val index = if (kids.size > 0) renderNode(kids[0]) else ""
            val body = if (kids.size > 1) {
                kids.drop(1).joinToString("") { renderNode(it) }
            } else ""
            return "\\sqrt[$index]{$body}"
        }
        "mspace" -> return " "
        // math, mrow, semantics, mfenced, tables and unknown tags:
        // best-effort concatenation of rendered children.
        else -> return renderKids(el)
    }
    return renderKids(el)
}

internal fun mathMlToLatex(mathml: String): String {
    if (mathml.isBlank()) return ""
    return try {
        renderKids(parseMathMl(mathml))
    } catch (_: Exception) {
        // Never throw: fall back to raw text with tags stripped.
        try {
            escapeLatex(unescapeEntities(mathml.replace(Regex("<[^>]*>"), "")))
        } catch (_: Exception) {
            ""
        }
    }
}
