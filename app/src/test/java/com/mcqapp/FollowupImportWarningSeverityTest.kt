package com.mcqapp

import com.mcqapp.domain.ImportWarningSeverity
import com.mcqapp.domain.ParserWarning
import com.mcqapp.domain.severityOfParserWarning
import com.mcqapp.domain.splitImportWarnings
import com.mcqapp.domain.splitTypedImportWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skipped/advisory split used to key off the substring "skipped" in the
 * message. A reworded advisory that happens to mention the word was shown as
 * lost content; a real drop whose message changed was shown as a harmless note.
 * Severity is now data carried with the warning.
 */
class FollowupImportWarningSeverityTest {

    @Test
    fun severityNotWordingDecidesThePanel() {
        val dropped = ParserWarning("A row was discarded for lacking a stem", ImportWarningSeverity.DROPPED)
        val advisory = ParserWarning("No rows were skipped — the file imported cleanly", ImportWarningSeverity.ADVISORY)
        val groups = splitTypedImportWarnings(listOf(dropped, advisory))

        assertEquals(listOf("A row was discarded for lacking a stem"), groups.skipped)
        assertEquals(listOf("No rows were skipped — the file imported cleanly"), groups.notes)
    }

    @Test
    fun legacyStringAdapterKeepsEveryCurrentMessageOnItsPanel() {
        val advisory = listOf(
            "File root is a scalar or null, not an object or array — no papers found",
            "Could not read the answer key: nope — question imports ungraded",
            "An equation uses unsupported constructs and was kept as plain text",
            "A previous import of this file stopped after 3 of 10 questions. No changes were saved"
        )
        val dropped = listOf(
            "Skipped malformed question 3: expected a list",
            "A picture points at a missing file (media/x.png) and was skipped.",
            "File: \"papers\" is a string, not a list — its contents were skipped"
        )
        val groups = splitImportWarnings(advisory + dropped)

        assertEquals(dropped.toSet(), groups.skipped.toSet())
        assertEquals(advisory.toSet(), groups.notes.toSet())
        assertEquals((advisory + dropped).toSet(), (groups.skipped + groups.notes).toSet())
    }

    @Test
    fun classifierPreservesTodaysSkippedMessages() {
        assertEquals(
            ImportWarningSeverity.DROPPED,
            severityOfParserWarning("Category 'X': \"questions\" is a scalar, not a list — its contents were skipped")
        )
        assertEquals(
            ImportWarningSeverity.DROPPED,
            severityOfParserWarning("A picture has no readable image reference and was skipped.")
        )
        assertEquals(
            ImportWarningSeverity.ADVISORY,
            severityOfParserWarning("Backup restore: 2 papers will be restored")
        )
    }

    @Test
    fun emptyWarningsStillMakeEmptyGroups() {
        assertTrue(splitTypedImportWarnings(emptyList()).isEmpty)
    }
}
