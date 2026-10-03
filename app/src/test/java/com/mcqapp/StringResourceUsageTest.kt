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

    private fun sources(): List<File> =
        File("src/main/java/com/mcqapp/ui").walkTopDown().filter { it.extension == "kt" }.toList()

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
                val rendered = before.endsWith("Text(") ||
                    before.endsWith("contentDescription =") ||
                    before.endsWith("label =")
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
    fun theResourcesFileIsNotEmptyOrTruncated() {
        val defined = definedResources()
        assertTrue("strings.xml looks truncated: ${defined.size} entries", defined.size > 150)
        assertTrue("app_name must survive", "app_name" in defined)
    }
}
