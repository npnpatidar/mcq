package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
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
}
