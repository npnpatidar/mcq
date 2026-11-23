package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.ImportNote
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.normalizedFlashcard
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.toContentJson
import com.mcqapp.ui.importscreen.changedPreviewBadges
import com.mcqapp.ui.importscreen.classifyPreviewDetailed
import com.mcqapp.ui.importscreen.importNoteRes
import com.mcqapp.ui.importscreen.previewHashOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-honesty pass made three preview facts explicit: a fixed answer key
 * can reset progress when the setting is on, a changed row may carry a real
 * schedule reset, and the preview must hash the same (downscaled) bytes the
 * importer stores. All three are pure rules and pinned here.
 */
class DataHonestyImportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dto(
        id: String,
        correct: List<String> = listOf("a"),
        optionText: String = "A"
    ) = QuestionDto(
        id = id,
        text = "One?",
        elements = listOf(ContentElement.TextElement("One?")),
        options = listOf(
            OptionDto(
                id = "a", text = optionText,
                elements = listOf(ContentElement.TextElement(optionText))
            )
        ),
        correctOptionIds = correct,
        explanation = "Because",
        explanationElements = listOf(ContentElement.TextElement("Because"))
    )

    private fun entity(id: String) = QuestionEntity(
        id = id,
        categoryId = "c1",
        text = listOf(ContentElement.TextElement("One?")).toContentJson(json),
        searchText = listOf(ContentElement.TextElement("One?")).searchableText,
        searchTextFolded = listOf(ContentElement.TextElement("One?")).searchableText.lowercase(),
        tags = "",
        tagsFolded = "",
        explanation = listOf(ContentElement.TextElement("Because")).toContentJson(json),
        contentHash = ContentHash.of(dto(id))
    )

    private fun option(id: String) = listOf(
        OptionEntity(
            questionId = id,
            id = "a",
            text = listOf(ContentElement.TextElement("A")).toContentJson(json),
            searchText = listOf(ContentElement.TextElement("A")).searchableText,
            searchTextFolded = listOf(ContentElement.TextElement("A")).searchableText.lowercase(),
            sortOrder = 0
        )
    )

    private fun classify(refresh: Boolean, correct: List<String> = listOf("b")) =
        classifyPreviewDetailed(
            hashed = listOf(dto("q1") to ContentHash.of(dto("q1"))),
            existingHashes = setOf(ContentHash.of(dto("q1"))),
            existingIds = setOf("q1"),
            storedById = mapOf("q1" to entity("q1")),
            correctById = mapOf("q1" to correct.toSet()),
            storedOptionsByQuestion = mapOf("q1" to option("q1")),
            refreshAnswers = refresh
        )

    @Test
    fun answerRefreshRequiresTheSetting() {
        val off = classify(refresh = false)
        assertTrue(off.answerRefresh.isEmpty())
        assertEquals(setOf("q1"), off.changed)

        val on = classify(refresh = true)
        assertEquals(setOf("q1"), on.answerRefresh)
        assertTrue(on.changed.isEmpty())
        assertTrue(on.duplicates.isEmpty())
    }

    @Test
    fun identicalAnswersAreNotAnAnswerRefresh() {
        val result = classify(refresh = true, correct = listOf("a"))
        assertTrue(result.answerRefresh.isEmpty())
        assertEquals(setOf("q1"), result.duplicates)
    }

    @Test
    fun aHashCollisionWithDifferentContentIsNotAnAnswerRefresh() {
        val result = classifyPreviewDetailed(
            hashed = listOf(dto("q1") to "shared-hash"),
            existingHashes = setOf("shared-hash"),
            existingIds = setOf("q1"),
            storedById = mapOf("q1" to entity("q1")),
            correctById = mapOf("q1" to setOf("b")),
            // A different stored option means a genuinely different question
            // hiding behind the hash, so no key may be refreshed onto it.
            storedOptionsByQuestion = mapOf(
                "q1" to listOf(
                    OptionEntity(
                        questionId = "q1", id = "a",
                        text = listOf(ContentElement.TextElement("Different")).toContentJson(json),
                        searchText = "Different", searchTextFolded = "different", sortOrder = 0
                    )
                )
            ),
            refreshAnswers = true
        )
        assertTrue(result.answerRefresh.isEmpty())
    }

    @Test
    fun previewHashUsesTheScaledTwin() {
        // Two options keep the picture on an option: the one-option shape is
        // normalised into a flashcard first, which drops the option entirely.
        val question = dto("q1").copy(
            options = listOf(
                OptionDto(
                    id = "a", text = "A", image = "data:image/png;base64,BIG",
                    elements = listOf(ContentElement.TextElement("A"))
                ),
                OptionDto(
                    id = "b", text = "B",
                    elements = listOf(ContentElement.TextElement("B"))
                )
            )
        )
        val unscaled = ContentHash.of(question.normalizedFlashcard())
        val scaledAway = previewHashOf(question) { "scaled-away" }.second
        assertNotEquals("the scaled bytes must change the hash", unscaled, scaledAway)
        assertEquals(unscaled, previewHashOf(question) { it }.second)
    }

    @Test
    fun changedBadgesNameBothLossesAndKeepTheTypeFirst() {
        assertEquals(
            listOf(
                R.string.r2_import_badge_flashcard,
                R.string.r2_import_badge_changed,
                R.string.data_honesty_badge_answer_refresh,
                R.string.data_honesty_badge_schedule_reset
            ),
            changedPreviewBadges(
                isFlashcard = true, answerRefresh = true, scheduleReset = true
            )
        )
        assertEquals(
            listOf(R.string.r2_import_badge_changed),
            changedPreviewBadges(false, false, false)
        )
    }

    @Test
    fun ankiNotesMapToCopyWithTheirCount() {
        val (resId, args) = importNoteRes(ImportNote.AnkiDroppedCards(4))
        assertEquals(R.string.data_honesty_anki_note_blank, resId)
        assertEquals(listOf(4), args)
        assertEquals(
            R.string.data_honesty_anki_note_media to emptyList<Any>(),
            importNoteRes(ImportNote.AnkiMissingMedia)
        )
    }
}
