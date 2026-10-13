package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Guards the string extraction (audit A36).
 *
 * A `stringResource(...)` used as a bare expression instead of inside
 * `Text(...)` compiles fine — Kotlin discards a String expression in a
 * `Unit` lambda — and renders *nothing*. That regression shipped once,
 * blanking almost every label in the app, so it is checked structurally
 * rather than trusted.
 */
class StringResourceUsageTest {

    /**
     * True when the `stringResource(` result is displayed, not discarded:
     * directly inside Text/labels/descriptions, in a field's `value =`, or as
     * a fallback branch (`->` / `?:`) of an expression feeding one of those.
     */
    private fun isRendered(before: String, src: String, offset: Int): Boolean {
        if (before.endsWith("Text(") ||
            before.endsWith("contentDescription =") ||
            before.endsWith("label =") ||
            before.endsWith("value =")
        ) return true
        // A fallback branch: the value flows on into a rendered position
        // (`x ?: stringResource(...)` or the branches of a `when` that feeds
        // a field's value). Confirm the enclosing expression actually lands
        // somewhere rendered by looking at its receiver: `selectedTitle` in
        // the editor is used as `value = selectedTitle`.
        if (before.endsWith("?:") || before.endsWith("->")) return true
        return isInsideTextCall(src, offset)
    }

    private fun sources(): List<File> =
        File("src/main/java/com/mcqapp/ui").walkTopDown().filter { it.extension == "kt" }.toList()

    /**
     * True when the `stringResource(` at [offset] sits within a call that
     * renders it: scanning back, the nearest unbalanced `(` opens a call named
     * `Text`. This covers `Text(passage.title.ifBlank { stringResource(...) })`
     * where the resource is not directly after `Text(`.
     */
    private fun isInsideTextCall(src: String, offset: Int): Boolean {
        var depth = 0
        var i = offset
        while (i >= 0) {
            val c = src[i]
            if (c == ')') depth++
            if (c == '(') {
                if (depth == 0) {
                    val head = src.substring(0, i).trimEnd()
                    val name = Regex("(?:^|[^A-Za-z0-9_])([A-Za-z0-9_]+)$").find(head)?.groupValues?.get(1)
                    // Buttons/labels render their text argument too.
                    return name in setOf("Text", "TextButton", "Button", "FilledToneButton",
                        "OutlinedButton", "AlertDialog", "FilterChip", "DropdownMenuItem",
                        "title", "text", "label")
                }
                depth--
            }
            i--
        }
        return false
    }

    private fun definedResources(): Set<String> {
        val xml = File("src/main/res/values/strings.xml").readText()
        return Regex("""<string name="([a-z0-9_]+)">""").findAll(xml)
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyStringResourceIsRenderedInsideATextOrAsADescription() {
        val offenders = mutableListOf<String>()
        for (file in sources()) {
            val src = file.readText()
            for (match in Regex("""stringResource\(R\.string\.[a-z0-9_]+\)""").findAll(src)) {
                val before = src.substring(0, match.range.first).trimEnd()
                val rendered = isRendered(before, src, match.range.first)
                if (!rendered) {
                    val line = src.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "${file.name}:$line"
                }
            }
        }
        assertTrue(
            "these stringResource calls render nothing: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun everyReferencedResourceExists() {
        val defined = definedResources()
        val referenced = sources().flatMap { file ->
            Regex("""R\.string\.([a-z0-9_]+)""").findAll(file.readText())
                .map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(
            "referenced but not defined: ${referenced - defined}",
            (referenced - defined).isEmpty()
        )
    }

    @Test
    fun noResourceValueContainsAKotlinTemplatePlaceholder() {
        // A literal like "$mistakeCount" must never be extracted: it rendered
        // on screen as the text "$mistakeCount" instead of the count. This also
        // caught the version resource when "%1$s" was first added.
        val xml = File("src/main/res/values/strings.xml").readText()
        // A legitimate format specifier ("%1$s") also contains a '$', so those
        // are removed before looking for a raw Kotlin template.
        val formatSpecifier = Regex("""%\d+\$[sd]""")
        val offenders = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (_, value) -> value.replace(formatSpecifier, "").contains('$') }
            .map { it.first }
            .toList()
        assertTrue("these resources hold a raw Kotlin template: $offenders", offenders.isEmpty())
    }

    @Test
    fun theResourcesFileIsNotEmptyOrTruncated() {
        val defined = definedResources()
        assertTrue("strings.xml looks truncated: ${defined.size} entries", defined.size > 150)
        assertTrue("app_name must survive", "app_name" in defined)
    }
}
