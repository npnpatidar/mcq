package com.mcqapp

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.importscreen.previewBadges
import com.mcqapp.ui.importscreen.previewQuestionsOf
import com.mcqapp.ui.search.backSnippetLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The flashcard labels a list row carries — Browse, Search, Bookmarks and a
 * passage's member list — plus the import preview's FLASHCARD badge and the
 * shape that preview renders (a one-option question shown as the card it
 * imports as), the renamed "No answer key (multiple choice)" chip and the
 * type-neutral `a11y_rich_content` description.
 *
 * Pure JVM on purpose: Compose cannot render on this host, so the labels are
 * pinned as *rendered text* in the sources (a contentDescription-only label
 * would not be seen by a sighted reader) and the copy is pinned in both
 * locales. The SQL that makes a back searchable has its own Robolectric test.
 */
class FlashcardRowsCopyTest {

    private fun source(path: String): String {
        val file = File("src/main/java/com/mcqapp/$path")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun strings(path: String): String = File("src/main/res/$path").readText()

    private fun value(xml: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private val english = strings("values/strings_round2_editor.xml") +
        strings("values/strings_round2_browse.xml") +
        strings("values/strings_round2_import.xml")

    private val hindi = strings("values-hi/strings_round2_editor_hi.xml") +
        strings("values-hi/strings_round2_browse_hi.xml") +
        strings("values-hi/strings_round2_import_hi.xml")

    /** Every row screen that shows a card's type as text. */
    private val rowScreens = listOf(
        "ui/browse/BrowseScreen.kt",
        "ui/search/SearchScreen.kt",
        "ui/history/BookmarksScreen.kt",
        "ui/editor/PassagesScreen.kt"
    )

    @Test
    fun everyRowShowsTheFlashcardLabelAsRenderedText() {
        for (path in rowScreens) {
            val src = source(path)
            val at = src.indexOf("R.string.r2_flashcard_label")
            assertTrue("$path must label a card row", at >= 0)
            // A description-only label is invisible on screen; the guard is
            // that the label sits inside the Text(...) that renders it.
            val before = src.substring(maxOf(0, at - 140), at)
            assertFalse(
                "$path renders the label as a contentDescription only: $before",
                before.contains("contentDescription")
            )
            assertTrue("$path must render the label in a Text", before.contains("Text("))
        }
    }

    @Test
    fun theFlashcardLabelIsTranslatedAndDiffers() {
        val en = value(english, "r2_flashcard_label")
        val hi = value(hindi, "r2_flashcard_label")
        assertEquals("Flashcard", en)
        assertTrue("r2_flashcard_label is missing from Hindi", hi.isNotBlank())
        assertTrue("r2_flashcard_label is not translated", en != hi)
    }

    @Test
    fun theNoAnswerChipNamesTheMultipleChoiceShape() {
        val en = value(english, "r2_browse_filter_no_answer")
        val hi = value(hindi, "r2_browse_filter_no_answer")
        assertFalse(
            "the chip must no longer read just 'No answer': $en",
            en.equals("No answer", ignoreCase = true)
        )
        assertTrue("the chip names the shape it filters for: $en", en.contains("multiple choice"))
        assertTrue("the Hindi chip is translated", hi.isNotBlank() && hi != en)
        // The chip label is what Browse's FlowRow renders (inside a Text).
        val browse = source("ui/browse/BrowseScreen.kt")
        assertTrue(browse.contains("R.string.r2_browse_filter_no_answer"))
    }

    @Test
    fun theBackOnlyHitSnippetIsLocalized() {
        val en = value(english, "r2_browse_answer_snippet")
        val hi = value(hindi, "r2_browse_answer_snippet")
        assertTrue("the snippet label starts with the answer word: $en", en.startsWith("Answer:"))
        assertTrue("the snippet carries the matched text: $en", en.contains("%1\$s"))
        assertTrue("the Hindi snippet is translated", hi.isNotBlank() && hi != en)
        assertTrue("the Hindi snippet keeps the placeholder: $hi", hi.contains("%1\$s"))
        assertTrue(
            "the search row renders the snippet",
            source("ui/search/SearchScreen.kt").contains("R.string.r2_browse_answer_snippet")
        )
    }

    /**
     * A back-only hit's label follows the row's type: a card's back is its
     * **Answer**, a multiple-choice question's back is its **Explanation**.
     * One shared "Answer:" label announced every explanation-only hit as an
     * answer, contradicting the Answer (card) / Explanation (MCQ) split the
     * rest of the app makes.
     */
    @Test
    fun aBackOnlyHitLabelsItsSnippetByTheQuestionsType() {
        assertEquals(
            "a card's snippet says Answer",
            R.string.r2_browse_answer_snippet,
            backSnippetLabelRes(isFlashcard = true)
        )
        assertEquals(
            "an MCQ's snippet says Explanation",
            R.string.r2_browse_explanation_snippet,
            backSnippetLabelRes(isFlashcard = false)
        )

        val answerEn = value(english, "r2_browse_answer_snippet")
        val explanationEn = value(english, "r2_browse_explanation_snippet")
        val answerHi = value(hindi, "r2_browse_answer_snippet")
        val explanationHi = value(hindi, "r2_browse_explanation_snippet")
        assertTrue("the answer label reads Answer: $answerEn", answerEn.startsWith("Answer:"))
        assertTrue("the explanation label reads Explanation: $explanationEn", explanationEn.startsWith("Explanation:"))
        assertTrue("the answer label is translated", answerHi.isNotBlank() && answerHi != answerEn)
        assertTrue("the explanation label is translated", explanationHi.isNotBlank() && explanationHi != explanationEn)
        assertTrue(
            "Hindi keeps the two words apart too: $answerHi / $explanationHi",
            answerHi != explanationHi
        )
        listOf(answerEn, explanationEn, answerHi, explanationHi).forEach { copy ->
            assertTrue("a snippet label keeps the placeholder: $copy", copy.contains("%1\$s"))
        }

        assertTrue(
            "the row chooses the label from the hit's own type",
            source("ui/search/SearchScreen.kt")
                .contains("backSnippetLabelRes(hit.question.isFlashcard)")
        )
    }

    @Test
    fun theImportBadgesAreResourcesNotLiterals() {
        val importSource = source("ui/importscreen/ImportScreen.kt")
        assertFalse(
            "a badge must not be a hardcoded literal",
            importSource.contains("badge = \"")
        )
        assertTrue(
            "a zero-option row is badged as a flashcard",
            importSource.contains("R.string.r2_import_badge_flashcard")
        )
        listOf(
            "r2_import_badge_flashcard",
            "r2_import_badge_changed",
            "r2_import_badge_duplicate"
        ).forEach { name ->
            val en = value(english, name)
            val hi = value(hindi, name)
            assertTrue("$name is missing from English", en.isNotBlank())
            assertTrue("$name is missing from Hindi", hi.isNotBlank())
            assertTrue("$name is not translated", en != hi)
        }
    }

    /**
     * A card can also be CHANGED or DUPLICATE, so the type badge is not a slot
     * that one of them wins: both are shown. This pins the decision to the
     * helper that builds the list; [previewBadgesKeepTheTypeFirst] proves the
     * actual order rather than the source shape.
     */
    @Test
    fun aCardKeepsItsFlashcardBadgeBesideItsStatusBadge() {
        val importSource = source("ui/importscreen/ImportScreen.kt")
        val helper = Regex("""internal fun previewBadges\([\s\S]*?\n\}""").find(importSource)
        assertTrue("previewBadges must be internal and testable", helper != null)
        val body = helper!!.value
        assertTrue(
            "the flashcard badge is added from the option list",
            body.contains("if (question.options.isEmpty()) add(R.string.r2_import_badge_flashcard)")
        )
        assertTrue("the status badge is kept too", body.contains("addAll(status.toList())"))
    }

    @Test
    fun previewBadgesKeepTheTypeFirst() {
        val card = QuestionDto(id = "card", options = emptyList())
        assertEquals(
            "a card that is also changed shows both, the type first",
            listOf(R.string.r2_import_badge_flashcard, R.string.r2_import_badge_changed),
            previewBadges(card, R.string.r2_import_badge_changed)
        )
        assertEquals(
            "the type alone on a plain new card",
            listOf(R.string.r2_import_badge_flashcard),
            previewBadges(card)
        )
        assertEquals(
            "a duplicate card keeps its type badge too",
            listOf(R.string.r2_import_badge_flashcard, R.string.r2_import_badge_duplicate),
            previewBadges(card, R.string.r2_import_badge_duplicate)
        )
        val multipleChoice = QuestionDto(
            id = "q",
            options = listOf(OptionDto(id = "a", text = "One"))
        )
        assertEquals(
            "a multiple-choice row shows only its status",
            listOf(R.string.r2_import_badge_changed),
            previewBadges(multipleChoice, R.string.r2_import_badge_changed)
        )
        assertEquals(
            "a plain new multiple-choice row is unbadged",
            emptyList<Int>(),
            previewBadges(multipleChoice)
        )
    }

    /**
     * The preview must render the shape the importer will store. A one-option
     * question imports as a flashcard (its option becomes the back), so the row
     * has to show the FLASHCARD badge, no option line, and the merged back —
     * otherwise the reader is told they are importing a choice that will not
     * exist. The id survives, which is what keeps a changed/duplicate row's
     * badge classification (id-keyed) working.
     */
    @Test
    fun aOneOptionPreviewRowRendersAsTheCardItImports() {
        val raw = QuestionDto(
            id = "q1",
            text = "What does photosynthesis release?",
            options = listOf(OptionDto(id = "a", text = "Oxygen")),
            correctOptionIds = listOf("a"),
            explanation = "Because it splits water."
        )

        val preview = previewQuestionsOf(listOf(raw)).single()

        assertEquals("the id keys the changed/duplicate classification", "q1", preview.id)
        assertTrue("a card has no option rows to render: ${preview.options}", preview.options.isEmpty())
        assertTrue("a card has no answer key: ${preview.correctOptionIds}", preview.correctOptionIds.isEmpty())
        assertEquals(
            "the option's answer comes first, then the existing explanation",
            listOf(
                ContentElement.TextElement("Oxygen"),
                ContentElement.TextElement("Because it splits water.")
            ),
            preview.explanationElements
        )
        assertTrue("the merged back is readable text: ${preview.explanation}", preview.explanation.contains("Oxygen"))
        assertEquals(
            "a one-option row is badged as the card it imports as",
            listOf(R.string.r2_import_badge_flashcard),
            previewBadges(preview)
        )
        assertEquals(
            "and it keeps the type badge beside a status badge",
            listOf(R.string.r2_import_badge_flashcard, R.string.r2_import_badge_changed),
            previewBadges(preview, R.string.r2_import_badge_changed)
        )
    }

    @Test
    fun aTwoOptionPreviewRowIsUnchanged() {
        val raw = QuestionDto(
            id = "q2",
            text = "Which gas is released?",
            options = listOf(OptionDto(id = "a", text = "Oxygen"), OptionDto(id = "b", text = "Nitrogen")),
            correctOptionIds = listOf("a"),
            explanationElements = listOf(ContentElement.TextElement("Chlorophyll."))
        )
        val preview = previewQuestionsOf(listOf(raw)).single()
        assertEquals("a real choice passes through untouched", raw, preview)
        assertEquals("and carries no type badge", emptyList<Int>(), previewBadges(preview))
        assertEquals(
            "only its status badge",
            listOf(R.string.r2_import_badge_duplicate),
            previewBadges(preview, R.string.r2_import_badge_duplicate)
        )
    }

    /**
     * A generated id is the prompt-only one, so two legacy one-option rows with
     * the same front and different single answers normalise to the same id. The
     * import writes by id — the later row replaces the earlier — so the preview
     * must collapse them the same way: two rows sharing a key crash Compose's
     * keyed list, and showing both promises an option the store never keeps.
     */
    @Test
    fun twoLegacyOneOptionRowsWithTheSameFrontCollapseToTheStoredCard() {
        val first = QuestionDto(
            id = "",
            text = "Same front",
            options = listOf(OptionDto(id = "a", text = "First answer"))
        )
        val second = QuestionDto(
            id = "",
            text = "Same front",
            options = listOf(OptionDto(id = "b", text = "Second answer"))
        )

        val rows = previewQuestionsOf(listOf(first, second))

        assertEquals("the importer writes one row", 1, rows.size)
        assertEquals("the preview's list keys are unique", 1, rows.map { it.id }.toSet().size)
        assertTrue("the normalised row has an id", rows.single().id.isNotBlank())
        assertEquals(
            "the later row wins, exactly as the write does",
            listOf(ContentElement.TextElement("Second answer")),
            rows.single().explanationElements
        )
        assertFalse(
            "the replaced answer is gone: ${rows.single().explanation}",
            rows.single().explanation.contains("First answer")
        )
        assertEquals(
            "and the one row is badged as a card",
            listOf(R.string.r2_import_badge_flashcard),
            previewBadges(rows.single())
        )
    }

    @Test
    fun aFilesOwnIdsAreNeverCollapsedByFront() {
        val first = QuestionDto(
            id = "file-1",
            text = "Same front",
            options = listOf(OptionDto(id = "a", text = "First answer"))
        )
        val second = QuestionDto(
            id = "file-2",
            text = "Same front",
            options = listOf(OptionDto(id = "b", text = "Second answer"))
        )
        assertEquals(
            "two authored ids stay two rows",
            2,
            previewQuestionsOf(listOf(first, second)).size
        )
    }

    @Test
    fun thePreviewRendersTheNormalisedRows() {
        val screen = source("ui/importscreen/ImportScreen.kt")
        assertTrue(
            "the preview rows are normalised once, before filtering",
            screen.contains("remember(state.questions) { previewQuestionsOf(state.questions) }")
        )
        assertTrue(
            "the new list partitions the normalised rows",
            screen.contains("previewQuestions.filter { it.id !in state.duplicateIds")
        )
        assertTrue(
            "a duplicate list too, so its FLASHCARD badge still shows",
            screen.contains("previewQuestions.filter { it.id in state.duplicateIds }")
        )
        assertTrue(
            "the editor opens the shape the import writes",
            screen.contains(".find { it.id == id }?.normalizedFlashcard()")
        )
    }

    @Test
    fun thePreviewCallsACardsBackAnAnswer() {
        val en = value(english, "r2_import_answer_label")
        val hi = value(hindi, "r2_import_answer_label")
        assertTrue("the preview's answer label starts with Answer: $en", en.startsWith("Answer:"))
        assertTrue("it carries the back text: $en", en.contains("%1\$s"))
        assertTrue("the Hindi label is translated", hi.isNotBlank() && hi != en)
        assertTrue("the Hindi label keeps the placeholder: $hi", hi.contains("%1\$s"))
        val screen = source("ui/importscreen/ImportScreen.kt")
        assertTrue(
            "a card's preview back uses the answer label",
            screen.contains("if (isCard) R.string.r2_import_answer_label")
        )
    }

    /**
     * A card's back is "Answer" in every locale and on every surface: the editor
     * label said "Back" in English while Hindi (and Study/Browse) already said
     * Answer, so the same control read two different things.
     */
    @Test
    fun aCardsBackIsAnAnswerInBothLocales() {
        val en = value(english, "r2_editor_flashcard_back")
        val hi = value(hindi, "r2_editor_flashcard_back")
        assertEquals("Answer", en)
        assertEquals(
            "the editor, Study and Browse must say the same thing",
            value(strings("values-hi/strings_round2_study_hi.xml"), "r2_study_answer_heading"),
            hi
        )
        assertEquals(
            "and the English copies agree too",
            value(strings("values/strings_round2_study.xml"), "r2_study_answer_heading"),
            en
        )
    }

    /**
     * The app's existing Hindi copy spells it फ़्लैशकार्ड (nukta); the new strings
     * had drifted to फ्लैशकार्ड in two files, so one word appeared two ways.
     */
    @Test
    fun hindiFlashcardCopyUsesTheAppsOneSpelling() {
        val files = File("src/main/res/values-hi").listFiles().orEmpty().filter { it.isFile }
        val drifted = files.filter { it.readText().contains("फ्लैशकार्ड") }.map { it.name }
        assertTrue(
            "Hindi flashcard copy must use फ़्लैशकार्ड, the spelling already in the app: $drifted",
            drifted.isEmpty()
        )
        assertTrue(
            "the app still carries the Hindi word at all",
            files.any { it.readText().contains("फ़्लैशकार्ड") }
        )
    }

    /**
     * The snippet explains a row's match, so it must be computed from the query
     * the rows were produced for (the debounced one), never from the live text
     * field: the field can already hold the next query while old rows are on
     * screen.
     */
    @Test
    fun theAnswerSnippetUsesTheCommittedQuery() {
        val viewModel = source("ui/search/SearchViewModel.kt")
        assertTrue(
            "the committed query must be exposed as state",
            viewModel.contains("val committedQuery: StateFlow<String>")
        )
        assertTrue(
            "one shared (not per-collector) debounce pipeline",
            viewModel.contains(".shareIn(")
        )
        val screen = source("ui/search/SearchScreen.kt")
        assertTrue(
            "the row snippet reads the committed query",
            screen.contains("backSnippet(hit.question, committedQuery)")
        )
        assertFalse(
            "the row snippet must not read the live text field",
            screen.contains("backSnippet(hit.question, state.query)")
        )
    }

    @Test
    fun theBrowseBackHeadingCallsACardsBackAnAnswer() {
        val browse = source("ui/browse/BrowseScreen.kt")
        assertTrue(
            "a card's back heading is Answer",
            browse.contains("if (question.isFlashcard) R.string.r2_study_answer_heading")
        )
        assertTrue(
            "a card's back image is an answer image",
            browse.contains("if (question.isFlashcard) R.string.r2_study_answer_image")
        )
    }

    @Test
    fun theRichContentDescriptionIsTypeNeutral() {
        val en = value(strings("values/strings.xml"), "a11y_rich_content")
        val hi = value(strings("values-hi/strings_hi_1.xml"), "a11y_rich_content")
        assertTrue("no question-only wording: $en", en.equals("Rendered content with formulas"))
        assertTrue("the Hindi description is translated", hi.isNotBlank() && hi != en)
        assertFalse(
            "the description must not say 'question': $hi",
            hi.contains("प्रश्न")
        )
    }

    /**
     * The prefilter/filter contract: every browse predicate that reads the text
     * also reads the back, and the five browse statements carry the same
     * "multiple choice only" no-answer arm. All five share one SQL text, so a
     * drifted copy is a real defect.
     */
    @Test
    fun theBrowseSqlSearchesTheBackAndExcludesCardsFromNoAnswer() {
        val daos = source("data/local/Daos.kt")
        val backBranches = Regex(
            """questions\.backTextFolded LIKE '%' \|\| :(?:query|fragment) \|\| '%' ESCAPE '\\\\'"""
        ).findAll(daos).count()
        assertEquals(
            "the 5 browse prefilters plus both search scopes must read the folded back: " +
                "$backBranches",
            // 1 per browse statement (5) + whole-query and fragment arm of each
            // search statement (2 × 2). The OPTIONS prefilter deliberately has
            // none: an answer is not an option.
            9,
            backBranches
        )
        val noAnswerArms = daos.lines().count {
            it.contains("AND EXISTS (SELECT 1 FROM options o WHERE o.questionId = questions.id)))")
        }
        assertEquals(
            "all five browse statements must exclude flashcards from the no-answer chip",
            5,
            noAnswerArms
        )
        assertEquals(
            "the chip's no-answer predicate is the one that requires options",
            5,
            daos.lines().count { it.contains("AND (:noAnswer = 0 OR (NOT EXISTS") }
        )
    }
}
