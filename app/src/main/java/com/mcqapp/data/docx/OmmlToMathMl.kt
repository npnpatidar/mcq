package com.mcqapp.data.docx

import com.mcqapp.data.normalizeMathText
import com.mcqapp.data.tokenizeMathRun
import org.w3c.dom.Element

/**
 * Office Math (OMML, `m:oMath`) to MathML, for the subset question banks
 * use: text runs, superscript/subscript, fractions and roots. Anything
 * else (n-ary operators, delimiters, boxes, visible root degrees, …)
 * returns null so the caller falls back to linear text plus a warning
 * instead of silently mangling the formula.
 */
internal const val MATH_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"

/** Full `<math>…</math>` or null when outside the subset. */
internal fun ommlToMathMl(oMath: Element, warnings: MutableList<String>, label: String): String? {
    val inner = ommlContent(oMath, warnings, label) ?: return null
    return "<math>$inner</math>"
}

/** Element children of [parent], skipping whitespace-only text. */
internal fun Element.childElements(): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) out.add(node as Element)
    }
    return out
}

private fun isMath(el: Element) = el.namespaceURI == MATH_NS

private fun ommlContent(parent: Element, warnings: MutableList<String>, label: String): String? {
    val sb = StringBuilder()
    val nodes = parent.childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        when (node.nodeType) {
            org.w3c.dom.Node.TEXT_NODE -> {
                // Pretty-printed whitespace between elements is layout.
                if (node.nodeValue.isNotBlank()) sb.append(tokenizeMathRun(node.nodeValue))
            }
            org.w3c.dom.Node.ELEMENT_NODE -> {
                val el = node as Element
                // Property containers (rPr, oMathPr, …) carry no content.
                if (isMath(el) && el.localName.endsWith("Pr")) continue
                sb.append(ommlElement(el, warnings, label) ?: return null)
            }
        }
    }
    return sb.toString()
}

private fun ommlElement(el: Element, warnings: MutableList<String>, label: String): String? {
    if (!isMath(el)) return null
    return when (el.localName) {
        // Run: tokenized text (mi/mn/mo) so the output is strict
        // MathML — bare text in mrow makes MathJax throw.
        "r" -> {
            val sb = StringBuilder()
            for (child in el.childElements()) {
                if (!isMath(child)) return null
                when (child.localName) {
                    "t" -> sb.append(tokenizeMathRun(child.textContent))
                    "rPr" -> Unit
                    "fldChar", "instrText", "delText", "br", "cr", "tab", "noBreakHyphen", "sym" ->
                        return null
                    else -> return null
                }
            }
            sb.toString()
        }
        "sSup", "sSub" -> {
            val parts = el.childElements().filter { isMath(it) && !it.localName.endsWith("Pr") }
            if (parts.size != 2) return null
            val (first, second) = parts
            if (first.localName != "e") return null
            val want = if (el.localName == "sSup") "sup" else "sub"
            if (second.localName != want) return null
            val tag = if (el.localName == "sSup") "msup" else "msub"
            val base = ommlContent(first, warnings, label) ?: return null
            val script = ommlContent(second, warnings, label) ?: return null
            "<$tag><mrow>$base</mrow><mrow>$script</mrow></$tag>"
        }
        "f" -> {
            val parts = el.childElements().filter { isMath(it) && !it.localName.endsWith("Pr") }
            if (parts.size != 2 || parts[0].localName != "num" || parts[1].localName != "den") return null
            val num = ommlContent(parts[0], warnings, label) ?: return null
            val den = ommlContent(parts[1], warnings, label) ?: return null
            "<mfrac><mrow>$num</mrow><mrow>$den</mrow></mfrac>"
        }
        "rad" -> {
            val kids = el.childElements().filter { isMath(it) && !it.localName.endsWith("Pr") }
            val deg = kids.firstOrNull { it.localName == "deg" }
            val body = kids.firstOrNull { it.localName == "e" }
            if (body == null) return null
            if (deg != null && deg.textContent.isNotBlank() && !degHidden(el)) {
                warnings.add(
                    "$label: root with a visible degree is kept as plain text " +
                        "— rewrite it as a square root or split the question"
                )
                return null
            }
            val content = ommlContent(body, warnings, label) ?: return null
            "<msqrt><mrow>$content</mrow></msqrt>"
        }
        // Structural wrappers: transparent containers.
        "e", "num", "den", "sup", "sub", "deg", "dPr", "oMathPara", "oMath" -> {
            val inner = ommlContent(el, warnings, label) ?: return null
            inner
        }
        else -> null
    }
}

private fun degHidden(rad: Element): Boolean {
    for (pr in rad.childElements()) {
        if (!isMath(pr) || pr.localName != "radPr") continue
        for (prop in pr.childElements()) {
            if (isMath(prop) && prop.localName == "degHide") {
                val v = prop.getAttribute("m:val").ifBlank { prop.getAttribute("val") }
                if (v.isBlank() || v == "1" || v.equals("on", ignoreCase = true) ||
                    v.equals("true", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
    }
    return false
}
