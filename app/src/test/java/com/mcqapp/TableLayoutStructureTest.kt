package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The read-only table is a hand-laid-out Compose grid, so nothing but its
 * source pins the row geometry down: a cell that does not fill its row stops
 * at its last line and draws a border across the middle of a taller
 * neighbour, which reads as a rowspan (Screenshot/table.jpg).
 */
class TableLayoutStructureTest {

    private val table: String = File("src/main/java/com/mcqapp/util/ContentElements.kt")
        .readText()
        .substringAfter("private fun TableElement(")
        .substringBefore("\n}\n")

    @Test
    fun everyRowIsAsTallAsItsTallestCell() {
        assertTrue(
            "the row must be sized to its tallest cell",
            table.contains("Row(modifier = Modifier.height(IntrinsicSize.Min))")
        )
        assertTrue(
            "every cell must fill the row height before it draws its border",
            Regex(
                """weight\(1f\)\s*\n\s*\.fillMaxHeight\(\)\s*\n\s*\.border\("""
            ).containsMatchIn(table)
        )
    }
}
