package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashCompatTest {
    @Test
    fun normalizedHashMatchesLegacyRendering() {
        val dto = QuestionDto(
            id = "q1", text = "2 + 2?",
            options = listOf(OptionDto("a", "4"), OptionDto("b", "five"))
        )
        val legacy = ContentHash.of(
            dto.text, dto.options.map { it.text }, dto.options.map { it.image }, dto.passageId
        )
        assertEquals(legacy, ContentHash.of(dto))
    }

    @Test
    fun aDifficultySpelledInAnotherCaseIsNotAChange() {
        // A bookmark or selection export writes the label ("Medium") and a
        // whole-bank export writes the stored value ("medium"), while
        // Difficulty.fromLabel reads both as the same difficulty. Comparing the
        // strings made a re-import of an unchanged question look like an update,
        // which refreshed it once for nothing.
        assertFalse(
            ContentHash.nonHashedFieldsDiffer(
                storedQuestion(difficulty = "medium"),
                setOf("a"),
                QuestionDto(id = "q1", difficulty = "Medium", correctOptionIds = listOf("a"))
            )
        )
    }

    @Test
    fun aRealDifficultyChangeIsStillAChange() {
        assertTrue(
            ContentHash.nonHashedFieldsDiffer(
                storedQuestion(difficulty = "medium"),
                setOf("a"),
                QuestionDto(id = "q1", difficulty = "hard", correctOptionIds = listOf("a"))
            )
        )
    }

    private fun storedQuestion(difficulty: String) = QuestionEntity(
        id = "q1",
        categoryId = "c1",
        text = "2 + 2?",
        searchText = "2 + 2?",
        difficulty = difficulty
    )
}
