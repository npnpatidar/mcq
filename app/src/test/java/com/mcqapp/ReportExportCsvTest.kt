package com.mcqapp

import com.mcqapp.data.export.ReportCsv
import com.mcqapp.data.export.ReportCsvHeader
import com.mcqapp.data.export.ReportCsvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** Pure JVM: slice 5 CSV export quoting, header, and column order. */
class ReportExportCsvTest {

    private fun header() = ReportCsvHeader(
        attemptId = 42L,
        paperTitle = "Sample Paper",
        testType = "RANDOM",
        family = "MOCK",
        exportedAtEpochMs = 0L
    )

    @Test
    fun `writes the header comment block and stable columns`() {
        val out = StringWriter()
        ReportCsv.write(out, header(), emptyList())
        val text = out.toString()
        assertTrue(text.contains("# attemptId=42"))
        assertTrue(text.contains("# type=RANDOM"))
        assertTrue(text.contains("# family=MOCK"))
        assertTrue(text.contains("number,questionId,category,result,dwellSeconds,tags"))
    }

    @Test
    fun `quotes commas quotes and newlines per rfc 4180`() {
        assertEquals("plain", ReportCsv.quote("plain"))
        assertEquals("\"a,b\"", ReportCsv.quote("a,b"))
        assertEquals("\"a\"\"b\"", ReportCsv.quote("a\"b"))
        assertEquals("\"a\nb\"", ReportCsv.quote("a\nb"))
        assertEquals("\"a\r\nb\"", ReportCsv.quote("a\r\nb"))
    }

    @Test
    fun `row values are quoted when needed and order is stable`() {
        val out = StringWriter()
        ReportCsv.write(
            out,
            header(),
            listOf(
                ReportCsvRow(
                    number = 1,
                    questionId = "q1",
                    category = "Algebra, basics",
                    result = "correct",
                    dwellSeconds = 12,
                    tags = "math,\"hard\""
                )
            )
        )
        val lines = out.toString().lines()
        val data = lines.first { it.startsWith("1,") }
        // category has a comma; tags has comma and quotes - all quoted
        assertTrue(data.contains("\"Algebra, basics\""))
        assertTrue(data.contains("\"math,\"\"hard\"\"\""))
        assertTrue(data.startsWith("1,q1,"))
    }

    @Test
    fun `non-ascii survives utf-8 writing`() {
        val out = StringWriter()
        ReportCsv.write(
            out,
            header().copy(paperTitle = "गणित"),
            listOf(ReportCsvRow(1, "q1", "बीज", "wrong", 5, "टैग"))
        )
        val text = out.toString()
        assertTrue(text.contains("गणित") || text.contains("# paper="))
        assertTrue(text.contains("बीज"))
    }

    @Test
    fun `multiple rows keep column order`() {
        val out = StringWriter()
        ReportCsv.write(
            out,
            header(),
            listOf(
                ReportCsvRow(1, "a", "c1", "correct", 3, ""),
                ReportCsvRow(2, "b", "c2", "skipped", 0, "t")
            )
        )
        val dataLines = out.toString().lines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(3, dataLines.size) // header + 2 rows
        assertTrue(dataLines[1].startsWith("1,"))
        assertTrue(dataLines[2].startsWith("2,"))
    }
}
