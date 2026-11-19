package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
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
     * and [isRenderedViaLocal] additionally proves a hoisted local is actually
     * consumed by one before accepting it.
     */
    private val feedbackSinks = setOf("announce", "sendMessage", "UiMessage")

    /**
     * Other real destinations for a resolved string. Curated from the call
     * sites the guard flagged: a chooser title, a settings error callback, a
     * share/export title and the spoken `onClickLabel`. A local is only accepted
     * when a use actually lands in one of these or in [renderingAssignments].
     */
    private val renderingSinkCalls = feedbackSinks + setOf(
        "createChooser", "onExportLogsError", "exportSelection"
    )

    /** Property assignments whose value is always spoken or shown. */
    private val renderingAssignments = listOf(
        "contentDescription =", "stateDescription =", "onClickLabel =",
        "label =", "help =", "value ="
    )

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
     * A resource is rendered when it is passed straight to a sink, or when it
     * is hoisted to a local `val` (needed because `stringResource` is
     * composable-only while callbacks/semantics lambdas are not) and that local
     * is later read by a sink. A hoisted resource with no sink use is still
     * reported — we trace the name through local-to-local and forwarding
     * functions, we do not assume.
     */
    private fun isRenderedViaLocal(before: String, src: String, offset: Int): Boolean {
        if (renderingSinkCalls.any { before.endsWith("$it(") }) return true
        if (renderingAssignments.any { before.endsWith(it) }) return true

        val hoisted = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
            .find(before)?.groupValues?.get(1) ?: return false
        return flowsToSink(setOf(hoisted), src, offset, mutableSetOf())
    }

    /** Follow each use of [names] until one lands in a real rendering sink. */
    private fun flowsToSink(
        names: Set<String>,
        src: String,
        from: Int,
        visited: MutableSet<String>
    ): Boolean {
        val fresh = names - visited
        if (fresh.isEmpty()) return false
        visited += fresh
        for (name in fresh) {
            for (use in Regex("""\b${Regex.escape(name)}\b""").findAll(src, from)) {
                val at = use.range.first
                val prior = src.substring(0, at).trimEnd()
                if (renderingAssignments.any { prior.endsWith(it) }) return true
                if (enclosingCall(src, at)?.first in renderingSinkCalls) return true

                // The local may be returned from a helper that is itself called
                // inside a sink (e.g. exportTitle() -> exportSelection(...)).
                val owner = returnedFromEnclosingFunction(name, src, at)
                if (owner != null && callsInsideSink(owner, src)) return true

                // Local-to-local flow: `val msg = "...$name..."` then a sink reads msg.
                val next = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=""")
                    .findAll(src.substring(maxOf(0, at - 400), at))
                    .lastOrNull()?.groupValues?.get(1)
                if (next != null && next !in visited && flowsToSink(setOf(next), src, at, visited)) {
                    return true
                }
            }
        }
        return false
    }

    /** Function whose body contains [at] and returns a value built from [name]. */
    private fun returnedFromEnclosingFunction(name: String, src: String, at: Int): String? {
        val owner = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]*>)?\s*\(""")
            .findAll(src.substring(0, at)).lastOrNull()?.groupValues?.get(1) ?: return null
        val window = src.substring(maxOf(0, at - 200), minOf(src.length, at + 200))
        return owner.takeIf {
            Regex("""\breturn\b[^;\n]*\b${Regex.escape(name)}\b""").containsMatchIn(window)
        }
    }

    /** True when [function] is invoked as an argument to one of the sinks. */
    private fun callsInsideSink(function: String, src: String): Boolean =
        Regex("""\b${Regex.escape(function)}\s*\(""").findAll(src).any { call ->
            enclosingCall(src, call.range.first)?.first in renderingSinkCalls
        }

    /**
     * `segmentLabels = listOf(stringResource(...), ...)` is a chart legend: the
     * chart folds these into its spoken `contentDescription`. Accept a resource
     * only while still inside such a list literal.
     */
    private fun isInsideSegmentLabels(src: String, offset: Int): Boolean {
        for (m in Regex("""segmentLabels\s*=\s*listOf\s*\(""").findAll(src)) {
            if (m.range.last >= offset) break
            var depth = 0
            for (i in m.range.last until offset) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) break
            }
            if (depth > 0) return true
        }
        return false
    }

    /** `stringResource(...) to SomeKey` is a chip/selector label paired with its key. */
    private fun isKeyedLabel(src: String, offset: Int): Boolean =
        Regex("""\)\s*to\s+[A-Za-z_]""").containsMatchIn(
            src.substring(offset, minOf(src.length, offset + 80))
        )

    /**
     * True only for a CONFIRMED rendered position: a direct sink, or a call
     * chain proven to end in one. Unlike [isRendered] this never accepts a bare
     * `->` / `?:` branch as proof — a nested resource must be rendered by a
     * confirmed enclosing call, not merely sit in a branch that might itself
     * be discarded.
     */
    private fun isRenderedConfirmed(before: String, src: String, offset: Int): Boolean {
        if (before.endsWith("Text(") ||
            renderingAssignments.any { before.endsWith(it) }
        ) return true
        if (isRenderedViaLocal(before, src, offset)) return true
        return isInsideTextCall(src, offset)
    }

    /**
     * True when the `stringResource(` result is displayed, not discarded:
     * directly inside Text/labels/descriptions, in a field's `value =`, or as
     * a fallback branch (`->` / `?:`) of an expression feeding one of those.
     */
    private fun isRendered(before: String, src: String, offset: Int): Boolean {
        if (before.endsWith("Text(") ||
            // AnkiNumberRow renders label and help verbatim into Texts in
            // its own body, so a call assigned to help is a rendered sink.
            renderingAssignments.any { before.endsWith(it) }
        ) return true
        if (isRenderedViaLocal(before, src, offset)) return true
        if (isInsideSegmentLabels(src, offset) || isKeyedLabel(src, offset)) return true
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
        val src = """val editorLabel = stringResource(R.string.a11y_formula_editor_value)
AndroidView(modifier = Modifier.semantics { contentDescription = editorLabel })"""
        val offset = src.indexOf("stringResource(R.string.a11y_formula_editor_value")
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

    /**
     * Regression: the Hindi time_taken string carried formatted="false" with
     * implicit %02d specifiers, so Android left it unformatted while English
     * rendered the minutes and seconds. Both locales must bind argument 1 to
     * minutes and argument 2 to seconds and actually produce an mm:ss string.
     */
    @Test
    fun timeTakenFormatsMinutesAndSecondsInBothLocales() {
        val locales = listOf(
            "src/main/res/values/strings.xml" to "Time taken: ",
            "src/main/res/values-hi/strings_hi_8.xml" to "लिया गया समय: "
        )
        locales.forEach { (path, prefix) ->
            val tag = Regex("""<string name="time_taken_02d_02d"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(File(path).readText())
            assertTrue("$path must declare time_taken_02d_02d", tag != null)
            assertFalse(
                "$path must let Android substitute the two arguments",
                "formatted=\"false\"" in tag!!.groupValues[1]
            )
            val value = tag.groupValues[2]
            assertEquals(
                "$path must use explicit positional specifiers",
                listOf(1 to 'd', 2 to 'd'),
                specifiersOf(value)
            )
            // Format with distinct real values: swapping the arguments would
            // give "07:05", so the output itself proves the binding order.
            val formatted = String.format(Locale.US, value, 5, 7)
            assertTrue("$path lost the translated prefix: $formatted", formatted.startsWith(prefix))
            assertTrue("$path did not render mm:ss: $formatted", formatted.endsWith("05:07"))
        }
    }

    // ---- Round-2 guard coverage (finding A7) --------------------------------
    //
    // The four syntactic patterns above cannot see a literal that is held in an
    // enum/data holder and handed to a resource seam as a parameter. Instead of
    // parsing arbitrary Kotlin, we keep a small curated registry of holders and
    // check the one mechanical invariant that makes them safe: every public
    // "label" String field is paired with a @StringRes field the UI can resolve.
    // The rule is written against source text so it can be unit-tested with
    // synthetic holders (see Round2GuardCoverageTest).

    /** A holder whose public label fields must be paired with string resources. */
    internal data class LabelHolder(
        val className: String,
        val sourcePath: String,
        /** Field names that are stable tokens/non-user-facing and need no resource. */
        val allowlist: Set<String> = emptySet()
    )

    internal val labelHolders = listOf(
        LabelHolder("ExportFormat", "src/main/java/com/mcqapp/data/export/ExportFormat.kt"),
        LabelHolder("Difficulty", "src/main/java/com/mcqapp/domain/Models.kt")
    )

    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val stringResAnnotation = Regex("""@(?:androidx\.annotation\.)?StringRes\b""")
    private val labelFieldName = Regex("""(?i)(label|title|description|message|text)$""")

    /** Strip annotations/modifiers from a constructor parameter, leaving `name: Type [= default]`. */
    private fun bareParam(param: String): String = param
        .replace(Regex("""@[A-Za-z0-9_.]+(?:\([^)]*\))?"""), "")
        .replace(Regex("""\b(?:val|var|private|internal|public|override|open|final|reified)\b"""), "")
        .trim()

    private fun fieldNameOf(bare: String): String? =
        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*:""").find(bare)?.groupValues?.get(1)

    private fun fieldTypeOf(bare: String): String? =
        Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*:\s*([A-Za-z0-9_?<>., ]+?)(?:\s*=|$)""")
            .find(bare)?.groupValues?.get(1)?.trim()

    /**
     * One violation per public `String` constructor field of [className] that
     * reads as user-facing copy but has no paired `@StringRes` field named
     * `<field>Res`. Accepts synthetic source so the rule itself is testable.
     */
    internal fun enumLabelPairingViolations(
        src: String,
        className: String,
        allowlist: Set<String> = emptySet()
    ): List<String> {
        // Block comments inside a constructor (KDoc on the fields) can carry
        // commas/parens that confuse a depth-based splitter, so strip them first.
        val clean = src.replace(blockComment, "")
        val decl = Regex("""\benum\s+class\s+${Regex.escape(className)}\s*\(""").find(clean)
            ?: return listOf("$className: enum declaration not found")
        val params = callArguments(clean, decl.range.last)
        if (params.isEmpty()) return listOf("$className: no constructor fields found")

        val resFields = params.mapNotNull { param ->
            val name = fieldNameOf(bareParam(param))
            if (name != null && stringResAnnotation.containsMatchIn(param)) name else null
        }.toSet()

        val offenders = mutableListOf<String>()
        for (param in params) {
            val bare = bareParam(param)
            if (Regex("""\bprivate\b""").containsMatchIn(param)) continue
            val name = fieldNameOf(bare) ?: continue
            val type = fieldTypeOf(bare) ?: continue
            if (!type.contains("String")) continue
            if (name in allowlist || !labelFieldName.containsMatchIn(name)) continue
            if ("${name}Res" !in resFields) {
                offenders += "$className.$name: user-facing label String with no paired @StringRes field"
            }
        }
        return offenders
    }

    /** Names in [all] that are absent from [present] — used for parity/seam checks. */
    internal fun missingFrom(all: Collection<String>, present: Set<String>): Set<String> =
        all.toSet() - present

    /** `<string name="...">` / `<plurals name="...">` names declared under a `res/values*` directory. */
    internal fun resourceNamesIn(directory: String): Set<String> =
        File(directory).walkTopDown()
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .flatMap { file ->
                Regex("""<(?:string|plurals) name="([a-z0-9_]+)"[^>]*>""")
                    .findAll(file.readText()).map { it.groupValues[1] }
            }.toSet()

    /** `R.string.<name>` / `R.plurals.<name>` names referenced in the main Kotlin sources. */
    internal fun referencedStringNames(): Set<String> =
        mainSources().flatMap { file ->
            Regex("""R\.(?:string|plurals)\.([a-z0-9_]+)""").findAll(file.readText())
                .map { it.groupValues[1] }
        }.toSet()

    internal data class ResourceSeam(
        val holder: String,
        val sourcePath: String,
        /** Hook the holder must expose so callers resolve copy through a resource. */
        val mustDeclare: List<String>,
        val resources: List<String>
    )

    /**
     * Round-2 seams: each holder exposes a localizable hook (`titleRes`,
     * `labelRes`, `warningKind`, ...) and every hook resolves to a resource that
     * must exist in BOTH locales and be referenced from real code. A dropped
     * hook, a missing translation, or a resource declared but never used through
     * a seam are all caught here.
     */
    internal val round2Seams = listOf(
        ResourceSeam(
            "ExportFormat",
            "src/main/java/com/mcqapp/data/export/ExportFormat.kt",
            listOf("titleRes", "descriptionRes", "@StringRes"),
            listOf(
                "r2_res_export_json_inline_title", "r2_res_export_json_inline_description",
                "r2_res_export_zip_title", "r2_res_export_zip_description",
                "r2_res_export_html_title", "r2_res_export_html_description",
                "r2_res_export_html_quiz_title", "r2_res_export_html_quiz_description",
                "r2_res_export_pdf_title", "r2_res_export_pdf_description",
                "r2_res_export_pdf_answer_key_title", "r2_res_export_pdf_answer_key_description",
                "r2_res_export_apkg_title", "r2_res_export_apkg_description",
                "r2_res_export_docx_title", "r2_res_export_docx_description"
            )
        ),
        ResourceSeam(
            "ReviewFilters",
            "src/main/java/com/mcqapp/domain/ReviewFilters.kt",
            listOf("labelRes"),
            listOf(
                "r2_res_filter_all", "r2_res_filter_correct", "r2_res_filter_wrong",
                "r2_res_filter_skipped", "r2_res_filter_ungraded", "r2_res_filter_slow",
                "r2_res_filter_saved"
            )
        ),
        ResourceSeam(
            "Difficulty",
            "src/main/java/com/mcqapp/domain/Models.kt",
            listOf("labelRes"),
            listOf("r2_editor_difficulty_easy", "r2_editor_difficulty_medium", "r2_editor_difficulty_hard")
        ),
        ResourceSeam(
            "TimerWarnings",
            "src/main/java/com/mcqapp/domain/TestTiming.kt",
            listOf("warningKind", "TimerWarningKind"),
            listOf("r2_test_warning_minutes", "r2_test_warning_one_minute", "r2_test_warning_seconds")
        ),
        ResourceSeam(
            "StudyOptionStates",
            "src/main/java/com/mcqapp/ui/study/StudyScreen.kt",
            listOf("studyOptionStateRes", "studyVerdictRes", "studyDwellBandRes", "@StringRes"),
            listOf(
                "r2_study_option_correct", "r2_study_option_selected_wrong",
                "r2_study_option_selected", "r2_study_option_not_selected",
                "r2_study_verdict_correct", "r2_study_verdict_incorrect",
                "r2_study_band_neutral", "r2_study_band_easy", "r2_study_band_good", "r2_study_band_hard"
            )
        )
    )

    @Test
    fun curatedEnumLabelFieldsArePairedWithStringResources() {
        val offenders = labelHolders.flatMap { holder ->
            enumLabelPairingViolations(File(holder.sourcePath).readText(), holder.className, holder.allowlist)
        }
        assertTrue("enum label fields with no @StringRes pair: $offenders", offenders.isEmpty())
    }

    @Test
    fun round2SeamsExistInBothLocalesAndAreReferencedFromCode() {
        val en = resourceNamesIn("src/main/res/values")
        val hi = resourceNamesIn("src/main/res/values-hi")
        val referenced = referencedStringNames()
        val offenders = mutableListOf<String>()
        for (seam in round2Seams) {
            val holderSrc = File(seam.sourcePath).readText()
            for (symbol in seam.mustDeclare) {
                val declared = if (symbol == "@StringRes") {
                    stringResAnnotation.containsMatchIn(holderSrc)
                } else {
                    symbol in holderSrc
                }
                if (!declared) offenders += "${seam.holder}: missing seam hook '$symbol'"
            }
            offenders += missingFrom(seam.resources, en).map { "${seam.holder}.$it missing in EN" }
            offenders += missingFrom(seam.resources, hi).map { "${seam.holder}.$it missing in HI" }
            offenders += missingFrom(seam.resources, referenced)
                .map { "${seam.holder}.$it declared but not used through a resource seam" }
        }
        assertTrue("broken round-2 resource seams: $offenders", offenders.isEmpty())
    }
}
