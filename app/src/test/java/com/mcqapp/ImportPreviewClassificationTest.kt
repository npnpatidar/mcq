package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.toContentJson
import com.mcqapp.ui.importscreen.classifyPreview
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview's new/changed/duplicate classification must match what the
 * importer actually does, or the badges lie about an import. The rule is pure
 * so it can be pinned here without a database; the row shapes are built the
 * same way the store writes them (elements JSON, positional options).
 */
class ImportPreviewClassificationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dto(
        id: String,
        text: String,
        optionTexts: List<String>,
        correct: List<String>
    ) = QuestionDto(
        id = id,
        text = text,
        elements = listOf(ContentElement.TextElement(text)),
        options = optionTexts.mapIndexed { index, t ->
            OptionDto(
                id = ('a' + index).toString(),
                text = t,
                elements = listOf(ContentElement.TextElement(t))
            )
        },
        correctOptionIds = correct,
        explanation = "Because",
        explanationElements = listOf(ContentElement.TextElement("Because"))
    )

    private fun entity(
        id: String,
        text: String,
        optionTexts: List<String>
    ) = QuestionEntity(
        id = id,
        categoryId = "c1",
        text = listOf(ContentElement.TextElement(text)).toContentJson(json),
        searchText = listOf(ContentElement.TextElement(text)).searchableText,
        searchTextFolded = listOf(ContentElement.TextElement(text)).searchableText.lowercase(),
        tags = "",
        tagsFolded = "",
        explanation = listOf(ContentElement.TextElement("Because")).toContentJson(json)
    )

    private fun options(questionId: String, optionTexts: List<String>) =
        optionTexts.mapIndexed { index, t ->
            OptionEntity(
                questionId = questionId,
                id = ('a' + index).toString(),
                text = listOf(ContentElement.TextElement(t)).toContentJson(json),
                searchText = listOf(ContentElement.TextElement(t)).searchableText,
                searchTextFolded = listOf(ContentElement.TextElement(t)).searchableText.lowercase(),
                sortOrder = index
            )
        }

    private fun classify(
        dto: QuestionDto,
        existingHashes: Set<String> = emptySet(),
        existingIds: Set<String> = emptySet(),
        stored: QuestionEntity? = null,
        storedOptionTexts: List<String> = emptyList(),
        storedCorrect: Set<String> = emptySet()
    ): Pair<Set<String>, Set<String>> {
        val hash = ContentHash.of(dto)
        val storedById = stored?.let { mapOf(it.id to it) } ?: emptyMap()
        val correctById = stored?.let { mapOf(it.id to storedCorrect) } ?: emptyMap()
        val optionsByQuestion = stored?.let { mapOf(it.id to options(it.id, storedOptionTexts)) } ?: emptyMap()
        return classifyPreview(
            hashed = listOf(dto to hash),
            existingHashes = existingHashes,
            existingIds = existingIds,
            storedById = storedById,
            correctById = correctById,
            storedOptionsByQuestion = optionsByQuestion
        )
    }

    @Test
    fun anUnseenQuestionIsNew() {
        val (dups, changed) = classify(dto("q1", "One?", listOf("A", "B"), listOf("a")))
        assertTrue(dups.isEmpty())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun anExistingIdWithDifferentContentIsChanged() {
        val q = dto("q1", "One?", listOf("A", "B"), listOf("a"))
        val (dups, changed) = classify(
            q,
            existingIds = setOf("q1"),
            stored = entity("q1", "Different?", listOf("A", "B")),
            storedOptionTexts = listOf("A", "B"),
            storedCorrect = setOf("a")
        )
        assertTrue(dups.isEmpty())
        assertEquals(setOf("q1"), changed)
    }

    @Test
    fun anIdenticalStoredQuestionIsADuplicate() {
        val q = dto("q1", "One?", listOf("A", "B"), listOf("a"))
        val (dups, changed) = classify(
            q,
            existingHashes = setOf(ContentHash.of(q)),
            existingIds = setOf("q1"),
            stored = entity("q1", "One?", listOf("A", "B")),
            storedOptionTexts = listOf("A", "B"),
            storedCorrect = setOf("a")
        )
        assertEquals(setOf("q1"), dups)
        assertTrue(changed.isEmpty())
    }

    /** Same content, but the file fixed the answer key: an update, not a dup. */
    @Test
    fun anIdenticalQuestionWithAFixedAnswerKeyIsChanged() {
        val q = dto("q1", "One?", listOf("A", "B"), listOf("b"))
        val (dups, changed) = classify(
            q,
            existingHashes = setOf(ContentHash.of(q)),
            existingIds = setOf("q1"),
            stored = entity("q1", "One?", listOf("A", "B")),
            storedOptionTexts = listOf("A", "B"),
            storedCorrect = setOf("a")
        )
        assertTrue(dups.isEmpty())
        assertEquals(setOf("q1"), changed)
    }

    /**
     * A hash collision whose content actually differs (a table the hash cannot
     * see) is a genuinely new question, not a duplicate.
     */
    @Test
    fun aHashCollisionWithDifferentContentIsNew() {
        val q = dto("q1", "One?", listOf("A", "B"), listOf("a"))
        val result = classifyPreview(
            hashed = listOf(q to "shared-hash"),
            existingHashes = setOf("shared-hash"),
            existingIds = emptySet(),
            storedById = mapOf("q1" to entity("q1", "One?", listOf("A", "B"))),
            correctById = mapOf("q1" to setOf("a")),
            storedOptionsByQuestion = mapOf("q1" to options("q1", listOf("A", "Different")))
        )
        assertTrue(result.first.isEmpty())
        assertTrue(result.second.isEmpty())
    }

    /**
     * The same content arriving under an id the library does not have: the
     * importer matches it by content hash and skips it as a duplicate, so the
     * preview must not count it as new.
     *
     * The rows the preview fetches are keyed by the incoming id, so the stored
     * twin lives under a different key — exactly the shape
     * [ImportViewModel.refreshDuplicates] produces for this case
     * (getByIdsChunked over the preview's own ids finds nothing).
     */
    @Test
    fun sameContentUnderADifferentIdIsADuplicateLikeTheImporter() {
        val q = dto("file-1", "One?", listOf("A", "B"), listOf("a"))
        val stored = entity("stored-9", "One?", listOf("A", "B"))
        val result = classifyPreview(
            hashed = listOf(q to ContentHash.of(q)),
            existingHashes = setOf(ContentHash.of(q)),
            existingIds = emptySet(),
            storedById = emptyMap(),
            correctById = emptyMap(),
            storedOptionsByQuestion = emptyMap()
        )
        assertEquals("preview must match the importer's content-hash dedup", setOf("file-1"), result.first)
        assertTrue(result.second.isEmpty())
    }
}
