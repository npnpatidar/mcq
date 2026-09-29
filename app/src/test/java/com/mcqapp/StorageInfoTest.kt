package com.mcqapp

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.StorageInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageInfoTest {

    @Test
    fun formatBytesScalesUnits() {
        assertEquals("0 B", StorageInfo.formatBytes(0))
        assertEquals("512 B", StorageInfo.formatBytes(512))
        assertEquals("1.5 KB", StorageInfo.formatBytes(1536))
        assertEquals("26.0 MB", StorageInfo.formatBytes(26L * 1024 * 1024))
        assertEquals("?", StorageInfo.formatBytes(-1))
    }

    @Test
    fun imageCharsSumAllPayloads() {
        val question = QuestionEntity(
            id = "q1",
            categoryId = "c1",
            text = "Q?",
            image = "12345",
            explanationImage = "123"
        )
        val options = listOf(
            OptionEntity(questionId = "q1", id = "a", text = "A", image = "12"),
            OptionEntity(questionId = "q1", id = "b", text = "B")
        )
        assertEquals(10L, StorageInfo.imageCharsOf(question, options))
    }

    @Test
    fun usageForPaperAggregates() {
        val questions = listOf(
            QuestionEntity(id = "q1", categoryId = "c1", text = "Q1", image = "1234"),
            QuestionEntity(id = "q2", categoryId = "c1", text = "Q2")
        )
        val byQuestion = mapOf(
            "q1" to listOf(OptionEntity(questionId = "q1", id = "a", text = "A", image = "12"))
        )
        val usage = StorageInfo.usageForPaper("p1", "Paper", questions, byQuestion)
        assertEquals(2, usage.questions)
        assertEquals(6L, usage.imageChars)
    }
}
