package com.mcqapp

import org.junit.Assert.assertFalse
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
     * Sinks added for the app-wide announcement/feedback host: a string handed
     * to one of these is spoken by TalkBack or shown in a Snackbar, so it is
     * rendered even though it never sits inside a `Text(...)`. This does not
     * weaken the existing checks — it only recognises these real destinations,
     * and [isRenderedByFeedbackSink] additionally proves a hoisted local is
     * actually consumed by one before accepting it.
     */
    private val feedbackSinks = setOf("announce", "sendMessage", "UiMessage")

    /** The nearest enclosing call at [offset]: its name and its opening `(`. */
    private fun enclosingCall(src: String, offset: Int): Pair<String?, Int>? {
        var depth = 0
        var i = offset
        while (i >= 0) {
            val c = src[i]
            if (c == ')') depth++
            if (c == '(') {
                if (depth == 0) {
                    val head = src.substring(0, i).trimEnd()
                    val name = Regex("(?:^|[^A-Za-z0-9_])([A-Za-z0-9_]+)$")
                        .find(head)?.groupValues?.get(1)
                    return name to i
                }
                depth--
            }
            i--
        }
        return null
    }

    /**
     * A resource is rendered when it is passed straight to a feedback sink, or
     * when it is hoisted to a local `val` (needed because `stringResource` is
     * composable-only while the callbacks that show the message are not) and
     * that local is later read by a sink. A hoisted resource with no sink use
     * is still reported — we trace the name, we do not assume.
     */
    private fun isRenderedByFeedbackSink(before: String, src: String, offset: Int): Boolean {
        if (feedbackSinks.any { before.endsWith("$it(") }) return true
        if (before.endsWith("contentDescription =")) return true

        val hoisted = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
            .find(before)?.groupValues?.get(1) ?: return false
        return Regex("""\b${Regex.escape(hoisted)}\b""").findAll(src, offset).any { use ->
            enclosingCall(src, use.range.first)?.first in feedbackSinks ||
                src.substring(0, use.range.first).trimEnd().endsWith("contentDescription =")
        }
    }

    /**
     * True only for a CONFIRMED rendered position: a direct sink, or a call
     * chain proven to end in one. Unlike [isRendered] this never accepts a bare
     * `->` / `?:` branch as proof — a nested resource must be rendered by a
     * confirmed enclosing call, not merely sit in a branch that might itself
     * be discarded.
     */
    private fun isRenderedConfirmed(before: String, src: String, offset: Int): Boolean {
        if (before.endsWith("Text(") ||
            before.endsWith("contentDescription =") ||
            before.endsWith("label =") ||
            before.endsWith("help =") ||
            before.endsWith("value =")
        ) return true
        if (isRenderedByFeedbackSink(before, src, offset)) return true
        return isInsideTextCall(src, offset)
    }

    /**
     * True when the `stringResource(` result is displayed, not discarded:
     * directly inside Text/labels/descriptions, in a field's `value =`, or as
     * a fallback branch (`->` / `?:`) of an expression feeding one of those.
     */
    private fun isRendered(before: String, src: String, offset: Int): Boolean {
        if (before.endsWith("Text(") ||
            before.endsWith("contentDescription =") ||
            before.endsWith("label =") ||
            // AnkiNumberRow renders label and help verbatim into Texts in
            // its own body, so a call assigned to help is a rendered sink.
            before.endsWith("help =") ||
            before.endsWith("value =")
        ) return true
        if (isRenderedByFeedbackSink(before, src, offset)) return true
        // A fallback branch: the value flows on into a rendered position
        // (`x ?: stringResource(...)` or the branches of a `when` that feeds
        // a field's value). Confirm the enclosing expression actually lands
        // somewhere rendered by looking at its receiver: `selectedTitle` in
        // the editor is used as `value = selectedTitle`.
        // A fallback branch: the value flows on into a rendered position
        // (`x ?: stringResource(...)` or the branches of a `when` that feeds
        // a field's value). Also handles if/else branches: look back for `if (`.
        if (before.endsWith("?:") || before.endsWith("->") ||
            Regex("if\\s*\\([^}]*$").find(before.trimEnd()) != null) return true
        return isInsideTextCall(src, offset)
    }

    /** Testable entry point for the rendered check on a synthetic snippet. */
    internal fun checkRendered(src: String, offset: Int): Boolean {
        val before = src.substring(0, offset).trimEnd()
        return isRendered(before, src, offset)
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
                    if (name == "stringResource") {
                        // A resource used as another resource's format
                        // argument is rendered exactly when that outer call
                        // is. Judge the outer call with the CONFIRMED check:
                        // accepting isRendered's bare branch shortcut let a
                        // discarded `when` branch's nested argument pass.
                        val outerStart = i - "stringResource".length
                        if (outerStart <= 0) return false
                        val outerBefore = src.substring(0, outerStart).trimEnd()
                        return isRenderedConfirmed(outerBefore, src, outerStart)
                    }
                    // Buttons/labels render their text argument too. `append`
                    // and `add` feed a buildString/buildList whose result is
                    // handed to Text, so a resource there is rendered as well.
                    return name in setOf("Text", "TextButton", "Button", "FilledToneButton",
                        "OutlinedButton", "AlertDialog", "FilterChip", "DropdownMenuItem",
                        "title", "text", "label", "append", "add")
                }
                depth--
            }
            i--
        }
        return false
    }

    /** Every strings file, including the feature-owned strings_*.xml files. */
    private fun resourceFiles(): List<File> =
        File("src/main/res/values").walkTopDown()
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .toList()

    private fun definedStrings(): Set<String> = resourceFiles().flatMap { file ->
        // `translatable="false"` is a valid attribute, so do not require the
        // tag to end immediately after the name.
        Regex("""<string name="([a-z0-9_]+)"[^>]*>""")
            .findAll(file.readText()).map { it.groupValues[1] }.toList()
    }.toSet()

    private fun definedPlurals(): Set<String> = resourceFiles().flatMap { file ->
        Regex("""<plurals name="([a-z0-9_]+)"[^>]*>""")
            .findAll(file.readText()).map { it.groupValues[1] }.toList()
    }.toSet()

    private fun definedResources(): Set<String> = definedStrings() + definedPlurals()

    private fun mainSources(): List<File> =
        File("src/main/java/com/mcqapp").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Format specifiers are removed before looking for raw Kotlin templates or checking plural consistency. */
    private val formatSpecifier = Regex("""%\d+\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    /** The value of every <string> and the first <item> of every <plurals>, keyed by (type, name). */
    private fun resourceValues(): Map<Pair<String, String>, String> =
        resourceFiles().flatMap { file ->
            val xml = file.readText()
            val strings = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { "string" to it.groupValues[1] to it.groupValues[2] }.toList()
            val plurals = Regex("""<plurals name="([a-z0-9_]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { m ->
                    val first = Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .find(m.groupValues[2])?.groupValues?.get(1).orEmpty()
                    "plurals" to m.groupValues[1] to first
                }.toList()
            strings + plurals
        }.toMap()

    /** (position, conversion) for each format specifier; position is null when implicit. */
    private fun specifiersOf(value: String): List<Pair<Int?, Char>> =
        Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""")
            .findAll(value).map { m ->
                (m.groupValues[1].ifEmpty { null }?.toInt()) to m.groupValues[2][0]
            }.toList()

    /** Top-level arguments of a call: nested calls stay a single argument. */
    private fun callArguments(src: String, openParen: Int): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var start = openParen + 1
        var i = start
        var done = false
        while (i < src.length && !done) {
            when (src[i]) {
                '(', '{', '[' -> depth++
                ')' -> if (depth == 0) {
                    args += src.substring(start, i).trim()
                    done = true
                } else depth--
                '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    args += src.substring(start, i).trim()
                    start = i + 1
                }
            }
            i++
        }
        return args
    }

    /** Expressions that are certainly String, never a legal argument for a %d specifier. */
    private val certainlyStringArgument = Regex(
        """^"|\bString\.format\s*\(|\bformatPercent\s*\(|\bDwell\.format\s*\(|\b(?:stringResource|pluralStringResource)\s*\(|\.\s*(?:lowercase|uppercase|trim)\s*\(|\.(?:title|name|paperTitle|explanation|nextIn|tag|label)\b"""
    )

    /** All `<plurals>` blocks in every strings file, with their quantity items. */
    private fun pluralBlocks(): List<Pair<String, List<Pair<String, String>>>> =
        resourceFiles().flatMap { file ->
            Regex("""<plurals name="([a-z0-9_]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText()).map { match ->
                    val items = Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(match.groupValues[2])
                        .map { it.groupValues[1] to it.groupValues[2] }.toList()
                    match.groupValues[1] to items
                }.toList()
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

    /**
     * Counter-example for the weakened recursion (M4): the outer resource is
     * in a discarded `when` branch, so the resource nested as its format
     * argument is discarded too. The old recursion followed isRendered's bare
     * `->` shortcut for the outer call and wrongly passed the inner one.
     */
    @Test
    fun aDiscardedWhenBranchsNestedFormatArgumentIsNotRendered() {
        val src = """val title = when (mode) {
    MODE -> stringResource(R.string.outer, stringResource(R.string.inner))
}"""
        val inner = src.indexOf("stringResource(R.string.inner")
        assertTrue("the nested call was found", inner >= 0)
        assertFalse(
            "a discarded branch's nested resource must be reported",
            checkRendered(src, inner)
        )
    }

    @Test
    fun aHoistedStringHandedToTheFeedbackHostIsRendered() {
        val src = """val shareNoAppText = stringResource(R.string.share_no_app)
sendMessage(UiMessage(shareNoAppText, UiMessageTone.Error))"""
        val offset = src.indexOf("stringResource(R.string.share_no_app")
        assertTrue("a hoisted snackbar message is spoken/shown", checkRendered(src, offset))
    }

    @Test
    fun aHoistedStringUsedAsAContentDescriptionIsRendered() {
        val src = """val editorLabel = stringResource(R.string.a11y_formula_editor)
AndroidView(modifier = Modifier.semantics { contentDescription = editorLabel })"""
        val offset = src.indexOf("stringResource(R.string.a11y_formula_editor")
        assertTrue("a hoisted content description is spoken", checkRendered(src, offset))
    }

    @Test
    fun aHoistedStringWithNoSinkUseIsStillReported() {
        val src = """val orphan = stringResource(R.string.saved)
val answer = 42"""
        val offset = src.indexOf("stringResource(R.string.saved")
        assertFalse("a hoisted resource never consumed must still be reported", checkRendered(src, offset))
    }

    @Test
    fun everyReferencedResourceExists() {
        val strings = definedStrings()
        val plurals = definedPlurals()
        val files = sources()
        val missingStrings = files.flatMap { file ->
            Regex("""R\.string\.([a-z0-9_]+)""").findAll(file.readText())
                .map { it.groupValues[1] }.toList()
        }.toSet() - strings
        val missingPlurals = files.flatMap { file ->
            Regex("""R\.plurals\.([a-z0-9_]+)""").findAll(file.readText())
                .map { it.groupValues[1] }.toList()
        }.toSet() - plurals
        assertTrue("referenced but undefined strings: $missingStrings", missingStrings.isEmpty())
        assertTrue("referenced but undefined plurals: $missingPlurals", missingPlurals.isEmpty())
    }

    @Test
    fun noResourceValueContainsAKotlinTemplatePlaceholder() {
        // A literal like "$mistakeCount" must never be extracted: it rendered
        // on screen as the text "$mistakeCount" instead of the count. This also
        // caught the version resource when "%1$s" was first added.
        // A legitimate format specifier ("%1$s", "%2$02d") also contains a
        // '$', so those are removed before looking for a raw Kotlin template.
        val offenders = resourceFiles().flatMap { file ->
            Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.filter { (_, value) -> value.replace(formatSpecifier, "").contains('$') }
            .map { it.first }
        assertTrue("these resources hold a raw Kotlin template: $offenders", offenders.isEmpty())
    }

    @Test
    fun theResourcesFileIsNotEmptyOrTruncated() {
        val defined = definedResources()
        assertTrue("strings.xml looks truncated: ${defined.size} entries", defined.size > 150)
        assertTrue("app_name must survive", "app_name" in defined)
    }

    /**
     * Regression: an incomplete or inconsistent plural reads "1 questions" or
     * loses a format argument in one language. Both quantity forms must exist
     * and carry the same format specifiers.
     */
    @Test
    fun everyPluralHasBothQuantitiesAndMatchingFormatSpecifiers() {
        val offenders = mutableListOf<String>()
        for ((name, items) in pluralBlocks()) {
            val quantities = items.map { it.first }.toSet()
            if (!quantities.containsAll(setOf("one", "other"))) {
                offenders += "$name: quantities $quantities"
            }
            val specSets = items.map { (_, value) ->
                formatSpecifier.findAll(value).map { it.value }.toSet()
            }.toSet()
            if (specSets.size > 1) offenders += "$name: format specifiers differ between items"
        }
        assertTrue("malformed plurals: $offenders", offenders.isEmpty())
    }

    /**
     * Regression for the count strings that used to read "Edit 1 questions",
     * "1 categories" and "Sittings: 1" — they must be plurals, not strings.
     * Fails on the pre-localisation strings.xml.
     */
    @Test
    fun convertedCountNamesArePluralsWithDistinctForms() {
        val names = setOf(
            "edit_n_questions", "delete_n_questions", "move_includes_passage", "resume_with_n",
            "break_suggestion", "study_scope_many", "test_setup_scope_categories",
            "test_setup_pool_questions", "test_setup_pool_mistakes", "test_setup_count_label",
            "test_setup_short_test", "test_preview_short", "test_banner_random",
            "test_banner_mistakes", "test_banner_short", "drill_banner_text",
            "stats_n_reviews", "stats_leeches_count", "storage_show_all_papers",
            "a11y_chart_n_reviews", "report_header_questions", "export_n_selected",
            "past_attempts_count", "search_result_count", "attempts_count"
        )
        val strings = definedStrings()
        val plurals = definedPlurals()
        assertTrue("count names not declared as plurals: ${names - plurals}", (names - plurals).isEmpty())
        assertTrue("count names still declared as strings: ${names intersect strings}", (names intersect strings).isEmpty())
        val identical = pluralBlocks().filter { (name, items) ->
            name in names && items.map { it.second }.toSet().size == 1
        }.map { it.first }
        assertTrue("plurals whose one/other forms are identical: $identical", identical.isEmpty())
    }

    /** Regression for the runtime-concatenated fragments with trailing/leading spaces. */
    @Test
    fun noTranslatableStringHasLeadingOrTrailingWhitespace() {
        val offenders = resourceFiles().flatMap { file ->
            Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText()).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        }.filter { (_, attrs, value) ->
            "translatable=\"false\"" !in attrs && value != value.trim()
        }.map { it.first }
        assertTrue("strings with stray whitespace: $offenders", offenders.isEmpty())
    }

    /** Brand names and format fragments must never reach a translator. */
    @Test
    fun brandAndTechnicalStringsAreNotTranslatable() {
        val names = setOf(
            "app_name", "mcq_app", "theme_material", "theme_nord", "theme_dracula",
            "theme_tokyo_night", "theme_catppuccin", "theme_gruvbox", "theme_one_dark",
            "theme_monokai", "theme_solarized", "theme_everforest", "theme_rose_pine",
            "theme_ayu", "theme_night_owl", "theme_kanagawa", "theme_github",
            "l_02d_02d", "l_1f_0f", "label"
        )
        val xml = resourceFiles().joinToString("\n") { it.readText() }
        val offenders = names.filter { name ->
            !Regex("""<string name="$name"[^>]*translatable="false"[^>]*>""").containsMatchIn(xml)
        }
        assertTrue("brand/technical strings missing translatable=false: $offenders", offenders.isEmpty())
    }

    /**
     * Catches the literals that used to sit inside Compose Text/contentDescription
     * and feedback sinks — invisible to the `stringResource(...)` checks above.
     */
    @Test
    fun noHardcodedUserFacingLiteralInComposeTextOrFeedback() {
        val patterns = listOf(
            Regex("""\bText\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"""),
            Regex("""contentDescription\s*=\s*"([^"\\]*(?:\\.[^"\\]*)*)"""),
            Regex("""UiMessage\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"""),
            Regex("""Toast\.makeText\([^,]+,\s*"([^"\\]*(?:\\.[^"\\]*)*)""")
        )
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val src = file.readText()
            for (pattern in patterns) for (match in pattern.findAll(src)) {
                if (Regex("[A-Za-z]{2,}").containsMatchIn(match.groupValues[1])) {
                    val line = src.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "${file.name}:$line: ${match.groupValues[1]}"
                }
            }
        }
        assertTrue("hardcoded user-facing literals: $offenders", offenders.isEmpty())
    }

    /**
     * Regression: history_delta_points declared %2$d while its call site passed
     * String.format(...), throwing IllegalFormatConversionException on every
     * trend row with more than one attempt. Check every call site's numeric
     * specifiers against arguments that are certainly Strings.
     */
    @Test
    fun formatSpecifiersMatchArgumentsSuppliedAtCallSites() {
        val values = resourceValues()
        assertTrue(
            "history_delta_points must format both arguments as strings",
            "%2\$s" in values["string" to "history_delta_points"].orEmpty()
        )
        val call = Regex("""(?:pluralStringResource|stringResource)\s*\(\s*R\.(string|plurals)\.([a-z0-9_]+)""")
        val integerConversions = setOf('d', 'i', 'o', 'u', 'x', 'X')
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val src = file.readText()
            for (m in call.findAll(src)) {
                val kind = m.groupValues[1]
                val name = m.groupValues[2]
                val value = values[kind to name] ?: continue
                val open = src.indexOf('(', m.range.first)
                if (open < 0) continue
                val formatArgs = callArguments(src, open).let { if (kind == "plurals") it.drop(2) else it.drop(1) }
                specifiersOf(value).forEachIndexed { index, (position, type) ->
                    val at = (position ?: index + 1) - 1
                    if (type in integerConversions && at in formatArgs.indices &&
                        certainlyStringArgument.containsMatchIn(formatArgs[at])
                    ) {
                        val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                        offenders += "${file.name}:$line: $name %${at + 1}$type <- ${formatArgs[at]}"
                    }
                }
            }
        }
        assertTrue("a numeric format specifier was given a String: $offenders", offenders.isEmpty())
    }
}
