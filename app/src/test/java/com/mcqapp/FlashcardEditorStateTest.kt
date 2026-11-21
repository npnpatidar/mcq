package com.mcqapp

import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.editor.EditorCardType
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.cardTypeOf
import com.mcqapp.ui.editor.editorCardFingerprint
import com.mcqapp.ui.editor.editorSnapshot
import com.mcqapp.ui.editor.explanationImageLabelRes
import com.mcqapp.ui.editor.saveBlockedHintRes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's save truth table once a question can be a flashcard: zero
 * options plus a visible back saves, zero options with an empty back does not,
 * one option is never a valid shape, and the multiple-choice rule is unchanged.
 *
 * Pure state (no Room, no Compose), so it runs on every host.
 */
class FlashcardEditorStateTest {

    private fun card(
        back: List<ContentElement> = listOf(ContentElement.TextElement("Paris")),
        backImage: String = ""
    ) = EditorUiState(
        loading = false,
        questionId = "card-1",
        categoryId = "c-1",
        isNew = false,
        elements = listOf(ContentElement.TextElement("Capital of France?")),
        cardType = EditorCardType.FLASHCARD,
        explanationElements = back,
        explanationImage = backImage
    )

    private fun question(vararg optionTexts: String) = EditorUiState(
        loading = false,
        questionId = "q-1",
        categoryId = "c-1",
        isNew = false,
        elements = listOf(ContentElement.TextElement("Which one?")),
        options = optionTexts.mapIndexed { i, text ->
            OptionEditorState(
                id = ('a' + i).toString(),
                elements = listOf(ContentElement.TextElement(text)),
                isCorrect = i == 0
            )
        },
        explanationElements = listOf(ContentElement.TextElement("Because."))
    )

    /** The two blank options switching back to a question seeds. */
    private fun blankOptions() = listOf(
        OptionEditorState("a", listOf(ContentElement.TextElement(""))),
        OptionEditorState("b", listOf(ContentElement.TextElement("")))
    )

    // ---- the truth table ----

    @Test
    fun zeroOptionsWithAVisibleBackSaves() {
        val card = card()
        assertTrue("a card with a back is savable", card.canSave)
        assertNull("nothing is missing", saveBlockedHintRes(card))
    }

    @Test
    fun zeroOptionsWithAnEmptyBackIsBlockedAndSaysWhy() {
        val card = card(back = listOf(ContentElement.TextElement("   ")))
        assertFalse("a card with nothing to reveal must not save", card.canSave)
        assertEquals(
            "the hint names the missing answer",
            R.string.r2_editor_save_blocked_flashcard_back,
            saveBlockedHintRes(card)
        )
        // The multiple-choice hint does not apply: the card has no options.
        assertFalse(
            "a card is never told to add options",
            saveBlockedHintRes(card) == R.string.save_blocked_two_options
        )
    }

    @Test
    fun aBackImageAloneIsAVisibleBack() {
        val card = card(back = emptyList(), backImage = "answer.png")
        assertTrue("an image-only back is still an answer", card.canSave)
    }

    @Test
    fun oneOptionIsNeverValid() {
        val question = question("Only")
        assertEquals(1, question.options.size)
        assertFalse("one option is an answer, not a choice", question.canSave)
        assertEquals(R.string.save_blocked_two_options, saveBlockedHintRes(question))

        // Nor is it a card: a card holds no options at all.
        val misfiled = card().copy(options = question.options)
        assertFalse(misfiled.canSave)
    }

    @Test
    fun multipleChoiceRuleIsUnchanged() {
        assertTrue(question("Alpha", "Beta").canSave)

        val withBlank = question("Alpha", "")
        assertFalse(withBlank.canSave)
        assertEquals(R.string.save_blocked_empty_option, saveBlockedHintRes(withBlank))

        val noFront = question("Alpha", "Beta").copy(elements = listOf(ContentElement.TextElement("")))
        assertFalse(noFront.canSave)
        assertEquals(R.string.save_blocked_no_content, saveBlockedHintRes(noFront))
    }

    /**
     * The MCQ hint order is the one `main` shipped, because the visible copy is
     * pinned by `QuestionEditorUiTest.blankNewQuestionExplainsTheDisabledSave`:
     * a brand-new question (two blank options, no front) blames its options, not
     * the empty text field.
     */
    @Test
    fun aBlankNewQuestionStillBlamesItsBlankOptions() {
        val fresh = EditorUiState(
            loading = false,
            questionId = "",
            paperId = "p-1",
            categoryId = "c-1",
            isNew = true,
            elements = listOf(ContentElement.TextElement("")),
            options = blankOptions()
        )
        assertEquals("the editor's default shape", EditorCardType.MULTIPLE_CHOICE, fresh.cardType)
        assertFalse(fresh.canSave)
        assertEquals(
            "options come before the front, as on main",
            R.string.save_blocked_empty_option,
            saveBlockedHintRes(fresh)
        )

        // The option-list checks still precede the front check in every
        // combination: too few options first...
        assertEquals(
            R.string.save_blocked_two_options,
            saveBlockedHintRes(fresh.copy(options = emptyList()))
        )
        // ...then a blank one, then (only with a complete option list) the front.
        assertEquals(
            R.string.save_blocked_empty_option,
            saveBlockedHintRes(fresh)
        )
        assertEquals(
            R.string.save_blocked_no_content,
            saveBlockedHintRes(
                question("Alpha", "Beta").copy(elements = listOf(ContentElement.TextElement("")))
            )
        )
    }

    /**
     * A card reports its missing answer first: the back is the piece a question
     * does not have, so it is what makes the form a card.
     */
    @Test
    fun aCardReportsItsMissingAnswerBeforeAMissingFront() {
        val blankCard = card(back = emptyList()).copy(
            elements = listOf(ContentElement.TextElement(""))
        )
        assertEquals(
            "the answer is the card's defining missing piece",
            R.string.r2_editor_save_blocked_flashcard_back,
            saveBlockedHintRes(blankCard)
        )

        val answeredButNoFront = card().copy(elements = listOf(ContentElement.TextElement("  ")))
        assertEquals(
            "with an answer present, the front is what is missing",
            R.string.save_blocked_no_content,
            saveBlockedHintRes(answeredButNoFront)
        )
    }

    @Test
    fun theCardTypeComesFromTheQuestion() {
        assertEquals("no options is a flashcard", EditorCardType.FLASHCARD, cardTypeOf(0))
        assertEquals("one option is a broken question, not a card", EditorCardType.MULTIPLE_CHOICE, cardTypeOf(1))
        assertEquals(EditorCardType.MULTIPLE_CHOICE, cardTypeOf(2))
    }

    // ---- the type is not a snapshot field of its own ----

    @Test
    fun theCardTypeAloneIsNotADirtyChange() {
        // The enum is implied by the option list. Two forms that would save the
        // same row must snapshot identically, or a no-op switch looks edited.
        val asCard = card()
        val sameShape = asCard.copy(cardType = EditorCardType.MULTIPLE_CHOICE)
        assertEquals(editorSnapshot(asCard), editorSnapshot(sameShape))

        // Every switch that really changes the type changes the option list,
        // and that list IS in the snapshot: clearing options...
        assertFalse(editorSnapshot(asCard) == editorSnapshot(question("Alpha", "Beta")))
        // ...and seeding the two blanks a question needs.
        assertFalse(editorSnapshot(asCard) == editorSnapshot(asCard.copy(options = blankOptions())))
    }

    // ---- the question/answer-list round trip ----

    @Test
    fun returningToTheSameShapeIsCleanAgain() {
        // card -> question (two blanks seeded) -> card is a no-op on save, so
        // the form reads clean again instead of offering to save nothing.
        val card = card()
        val seeded = card.copy(options = blankOptions())
        val backToCard = seeded.copy(options = emptyList())
        assertEquals(editorSnapshot(card), editorSnapshot(backToCard))
    }
}

/**
 * The new editor copy exists in both locales and is really translated.
 *
 * Pure file reads, so this runs on every host — the resource-parity checks for
 * the older files are per-batch tests like this one.
 */
class FlashcardEditorCopyTest {

    private fun value(path: String, name: String): String {
        val xml = File(path).readText()
        val match = Regex(
            """<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(xml)
        return match?.groupValues?.get(1).orEmpty()
    }

    @Test
    fun theCardTypeCopyExistsInBothLocalesAndDiffers() {
        val keys = listOf(
            "r2_editor_card_type",
            "r2_editor_card_type_multiple_choice",
            "r2_editor_card_type_flashcard",
            "r2_editor_flashcard_back",
            "r2_editor_switch_to_flashcard_title",
            "r2_editor_switch_to_flashcard_message",
            "r2_editor_switch_confirm"
        )
        keys.forEach { name ->
            val en = value("src/main/res/values/strings_round2_editor.xml", name)
            val hi = value("src/main/res/values-hi/strings_round2_editor_hi.xml", name)
            assertTrue("$name is missing from English", en.isNotBlank())
            assertTrue("$name is missing from Hindi", hi.isNotBlank())
            assertTrue("$name is not translated", en != hi)
            assertFalse("$name must carry no format arguments", en.contains("%"))
        }
    }

    @Test
    fun theFlashcardSaveHintSaysWhatIsMissing() {
        val en = value(
            "src/main/res/values/strings_round2_editor.xml",
            "r2_editor_save_blocked_flashcard_back"
        )
        val hi = value(
            "src/main/res/values-hi/strings_round2_editor_hi.xml",
            "r2_editor_save_blocked_flashcard_back"
        )
        assertEquals(
            "the hint names the missing answer, in the owner's words",
            "Add the answer to save this flashcard.",
            en
        )
        assertTrue("the Hindi hint must be translated", hi != en)
        assertTrue("the Hindi hint must name the answer", hi.contains("उत्तर"))
    }

    // ---- a card's back image is an answer, not an explanation ----

    @Test
    fun aCardsBackImageIsLabelledAnAnswerAndAQuestionsAnExplanation() {
        assertEquals(
            "a card's picture is its answer",
            R.string.r2_study_answer_image,
            explanationImageLabelRes(EditorCardType.FLASHCARD)
        )
        assertEquals(
            "a multiple-choice question keeps the explanation label",
            R.string.explanation_image,
            explanationImageLabelRes(EditorCardType.MULTIPLE_CHOICE)
        )
        assertFalse(
            "the two labels must not collapse into one",
            explanationImageLabelRes(EditorCardType.FLASHCARD) ==
                explanationImageLabelRes(EditorCardType.MULTIPLE_CHOICE)
        )
    }

    @Test
    fun theEditorRowResolvesTheTypeAwareLabel() {
        // The label must come from the card type, not a fixed "Explanation
        // image" string on the back-image row.
        val screen = File("src/main/java/com/mcqapp/ui/editor/QuestionEditorScreen.kt").readText()
        assertTrue(
            "the back-image row must resolve the type-aware label",
            screen.contains("label = stringResource(explanationImageLabelRes(state.cardType))")
        )
    }

    @Test
    fun everySurfaceNamesACardBackImageTheSameWay() {
        // Study and Browse already call a card's picture "Answer image"; the
        // editor must resolve the same resource rather than a fourth wording.
        listOf(
            "src/main/java/com/mcqapp/ui/study/StudyScreen.kt",
            "src/main/java/com/mcqapp/ui/browse/BrowseScreen.kt"
        ).forEach { path ->
            val src = File(path).readText()
            assertTrue(
                "$path must name a card's back image via r2_study_answer_image",
                src.contains("R.string.r2_study_answer_image")
            )
            assertTrue(
                "$path must keep the explanation label for a question",
                src.contains("R.string.explanation_image")
            )
        }
    }

    @Test
    fun bothBackImageLabelsSayTheRightThingInBothLocales() {
        val enAnswer = value("src/main/res/values/strings_round2_study.xml", "r2_study_answer_image")
        val enExplanation = value("src/main/res/values/strings.xml", "explanation_image")
        assertEquals("Answer image", enAnswer)
        assertEquals("Explanation image", enExplanation)

        val hiAnswer = value("src/main/res/values-hi/strings_round2_study_hi.xml", "r2_study_answer_image")
        val hiExplanation = value("src/main/res/values-hi/strings_hi_1.xml", "explanation_image")
        assertTrue("the Hindi answer label is missing", hiAnswer.isNotBlank())
        assertTrue("the Hindi explanation label is missing", hiExplanation.isNotBlank())
        assertTrue("the Hindi answer label must say answer", hiAnswer.contains("उत्तर"))
        assertTrue("the Hindi explanation label must say explanation", hiExplanation.contains("व्याख्या"))
        assertFalse("the two Hindi labels must differ", hiAnswer == hiExplanation)
    }
}

/**
 * The "saving this edit restarts the schedule" note for flashcards.
 *
 * [CardContent] folds a flashcard's back into the fingerprint because the back
 * *is* the answer; the editor side must do the same or editing an answer would
 * look like an explanation-only edit and never warn.
 */
class FlashcardScheduleDisclosureTest {

    private fun card(explanation: List<ContentElement> = listOf(ContentElement.TextElement("Paris"))) =
        EditorUiState(
            loading = false,
            questionId = "card-1",
            categoryId = "c-1",
            isNew = false,
            elements = listOf(ContentElement.TextElement("Capital of France?")),
            cardType = EditorCardType.FLASHCARD,
            explanationElements = explanation
        )

    private fun question() = EditorUiState(
        loading = false,
        questionId = "q-1",
        categoryId = "c-1",
        isNew = false,
        elements = listOf(ContentElement.TextElement("Which one?")),
        options = listOf(
            OptionEditorState("a", listOf(ContentElement.TextElement("Alpha")), isCorrect = true),
            OptionEditorState("b", listOf(ContentElement.TextElement("Beta")))
        ),
        explanationElements = listOf(ContentElement.TextElement("Because."))
    )

    private fun persisted(s: EditorUiState) =
        s.copy(baselineCardFingerprint = editorCardFingerprint(s))

    @Test
    fun aCleanCardShowsNoNote() {
        assertFalse(persisted(card()).willResetSchedule)
    }

    @Test
    fun editingACardsBackShowsTheNote() {
        val stored = persisted(card())
        assertTrue(
            "the back is the answer the learner studied",
            stored.copy(explanationElements = listOf(ContentElement.TextElement("Lyon")))
                .willResetSchedule
        )
    }

    @Test
    fun editingACardsBackImageShowsTheNote() {
        val stored = persisted(card())
        assertTrue(stored.copy(explanationImage = "new-answer.png").willResetSchedule)
    }

    @Test
    fun anEditToAQuestionsExplanationStillShowsNoNote() {
        val stored = persisted(question())
        assertFalse(
            "a question's explanation is shown after answering, not the answer itself",
            stored.copy(
                explanationElements = listOf(ContentElement.TextElement("A longer explanation.")),
                explanationImage = "new.png"
            ).willResetSchedule
        )
    }

    @Test
    fun theCardFingerprintMatchesWhatTheSavedDtoWouldCarry() {
        val s = card().copy(explanationImage = "  answer.png  ")
        // buildDto() trims scalar images to null when blank and keeps the back
        // blocks; mirror exactly that here.
        val dto = QuestionDto(
            id = s.questionId,
            elements = s.elements,
            options = emptyList(),
            correctOptionIds = emptyList(),
            explanationElements = s.explanationElements,
            explanationImage = s.explanationImage.trim().ifBlank { null }
        )
        assertEquals(CardContent.of(dto), editorCardFingerprint(s))
        assertFalse(
            s.copy(baselineCardFingerprint = editorCardFingerprint(s)).willResetSchedule
        )
        assertNotNull(s.copy(baselineCardFingerprint = editorCardFingerprint(s)).baselineCardFingerprint)
    }
}
