package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Seeded differential fuzz: random JSON shapes must never crash the
 * parser, and every parsed question must satisfy the structural contract
 * (non-blank ids, answer keys resolving to real options).
 */
class ParserFuzzTest {

    private val keys = listOf(
        "papers", "categories", "sections", "questions", "options",
        "text", "question", "title", "name", "id", "correctOptionIds",
        "correctIndex", "answer", "correct", "marks", "points",
        "explanation", "tags", "image", "difficulty", "junk", "x"
    )
    private val words = listOf("A", "B", "", " alpha ", "1", "true", "null", "Q?")

    private fun genValue(random: Random, depth: Int): String {
        if (depth > 2) return genScalar(random)
        return when (random.nextInt(6)) {
            0 -> genScalar(random)
            1 -> "null"
            2 -> (0 until random.nextInt(4)).joinToString(",", "[", "]") {
                genValue(random, depth + 1)
            }
            else -> (0 until random.nextInt(5)).joinToString(",", "{", "}") {
                "\"${keys.random(random)}\":${genValue(random, depth + 1)}"
            }
        }
    }

    private fun genScalar(random: Random): String = when (random.nextInt(6)) {
        0 -> "\"${words.random(random)}\""
        1 -> random.nextInt(-5, 300).toString()
        2 -> random.nextDouble(-10.0, 10.0).toString()
        3 -> if (random.nextBoolean()) "true" else "false"
        4 -> "[\"${words.random(random)}\",${random.nextInt(4)}]"
        else -> "{\"id\":\"${words.random(random)}\"}"
    }

    @Test
    fun neverThrowsAndKeepsContract() {
        for (seed in listOf(1L, 7L, 42L, 99L, 1234L)) {
            val random = Random(seed)
            repeat(200) { i ->
                val json = genValue(random, 0)
                try {
                    val file = LegacyParser.parse(json)
                    for (paper in file.papers) {
                        for (category in paper.categories) {
                            for (q in category.questions) {
                                assertTrue("seed=$seed case=$i blank id", q.id.isNotBlank())
                                val optionIds = q.options.map { it.id }.toSet()
                                assertTrue(
                                    "seed=$seed case=$i dangling key ${q.correctOptionIds}",
                                    q.correctOptionIds.all { it in optionIds }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    fail("seed=$seed case=$i json=$json threw ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }
}
