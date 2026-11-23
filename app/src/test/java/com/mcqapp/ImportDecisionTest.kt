package com.mcqapp

import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.ImportDecision
import com.mcqapp.data.io.ImportDecision.StoredCandidate
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.allDuplicatesPaperSkip
import com.mcqapp.data.io.cardScheduleKey
import com.mcqapp.data.io.categoryIdForWrite
import com.mcqapp.data.io.countPaper
import com.mcqapp.data.io.contentChanged
import com.mcqapp.data.io.isSameContent
import com.mcqapp.data.io.needsAnswerRefresh
import com.mcqapp.data.io.passageIdForWrite
import com.mcqapp.data.io.questionIdForWrite
import com.mcqapp.data.io.refreshInvalidatesSchedule
import com.mcqapp.data.io.scheduleFor
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.toContentJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the decision seam both importers call. These are the
 * rules that used to be spelled out twice, where a hash-only match could be
 * mistaken for the same question and a same-paper id could be remapped twice.
 * `StreamingImporterEquivalenceTest` remains the end-to-end safety net for the
 * database wiring; this class pins the decisions themselves without Room.
 */
class ImportDecisionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun textElements(text: String) = listOf(ContentElement.TextElement(text))

    private fun dto(
        id: String = "q1",
        text: String = "Stem",
        optionText: String = "right",
        correct: String = "a",
        elements: List<ContentElement> = emptyList()
    ) = QuestionDto(
        id = id,
        text = text,
        elements = elements,
        options = listOf(OptionDto(id = "a", text = optionText)),
        correctOptionIds = listOf(correct)
    )

    private fun entity(
        id: String = "q1",
        text: String = "Stem",
        hash: String = "h",
        elements: List<ContentElement> = emptyList()
    ): QuestionEntity {
        val blocks = elements.ifEmpty { textElements(text) }
        return QuestionEntity(
            id = id,
            categoryId = "c",
            text = blocks.toContentJson(json),
            searchText = text,
            searchTextFolded = text.lowercase(),
            explanation = "[]",
            tags = "",
            tagsFolded = "",
            contentHash = hash
        )
    }

    private fun option(questionId: String = "q1", id: String = "a") = OptionEntity(
        questionId = questionId,
        id = id,
        text = textElements("right").toContentJson(json),
        searchText = "right",
        searchTextFolded = "right"
    )

    private fun candidate(
        entity: QuestionEntity = entity(),
        options: List<OptionEntity> = listOf(option()),
        correctIds: Set<String> = setOf("a")
    ) = StoredCandidate(entity, options, correctIds)

    // --- candidate matching ------------------------------------------------

    @Test
    fun candidateForPicksTheRowWithIdenticalContentNotJustTheSameHash() {
        // A table is invisible to ContentHash, so both rows carry hash "h".
        // Only the second is the same question; refreshing the first would
        // write this file's key onto an unrelated row.
        val collision = candidate(
            entity = entity(id = "other", elements = textElements("Stem") + listOf(ContentElement.TableElement(listOf(listOf("x")))))
        )
        val real = candidate(entity = entity(id = "q1"))
        val found = ImportDecision.candidateFor(
            "h", dto(), mapOf("h" to listOf(collision, real))
        )
        assertSame(real, found)
    }

    @Test
    fun candidateForReturnsNullWhenNoStoredRowIsReallyTheSameQuestion() {
        val collision = candidate(entity = entity(id = "other", text = "Different"))
        assertNull(ImportDecision.candidateFor("h", dto(), mapOf("h" to listOf(collision))))
    }

    @Test
    fun isSameContentCountsQuestionsWrittenEarlierInThisImport() {
        // The hash pre-filter found nothing in the database, but this file
        // already wrote the same question: it must still collapse to one.
        val written = mapOf("h" to listOf(dto()))
        assertTrue(ImportDecision.isSameContent("h", dto(), emptyMap(), written))
        assertFalse(
            ImportDecision.isSameContent(
                "h", dto(elements = textElements("Stem") + listOf(ContentElement.TableElement(listOf(listOf("x"))))),
                emptyMap(), written
            )
        )
    }

    @Test
    fun isSameContentAcceptsAStoredRowWhoseContentReallyMatches() {
        assertTrue(
            ImportDecision.isSameContent("h", dto(), mapOf("h" to listOf(candidate())))
        )
    }

    // --- remapping ---------------------------------------------------------

    @Test
    fun remappingKeepsIdsOwnedByThisPaperAndRemapsForeignOnes() {
        assertEquals("q1", ImportDecision.questionIdForWrite("q1", "p1", "p1"))
        // The prefix is the DESTINATION paper id — that is the rule both
        // importers actually write, so the remapped row stays unique here.
        assertEquals("p1-q1", ImportDecision.questionIdForWrite("q1", "p2", "p1"))
        assertEquals("q1", ImportDecision.questionIdForWrite("q1", null, "p1"))
        assertEquals("cat", ImportDecision.categoryIdForWrite("cat", "p1", "p1"))
        assertEquals("p1-cat", ImportDecision.categoryIdForWrite("cat", "p2", "p1"))
        assertEquals("matched", ImportDecision.categoryIdForWrite("cat", "p2", "p1", "matched"))
        assertEquals("ps", ImportDecision.passageIdForWrite("ps", "p1", "p1"))
        assertEquals("p1-ps", ImportDecision.passageIdForWrite("ps", "p2", "p1"))
    }

    @Test
    fun remappingAdoptsAnIdAlreadyWrittenThisImportOnTheSamePaper() {
        // The streaming path adopts a row it wrote on an earlier batch; a row
        // owned by another paper is remapped instead of adopted.
        assertEquals(
            "p1-q1",
            ImportDecision.questionIdForWrite("q1", "p2", "p1", adoptId = "p2-q1")
        )
        assertEquals(
            "written-q1",
            ImportDecision.questionIdForWrite("q1", "p1", "p1", adoptId = "written-q1")
        )
    }

    // --- schedule decisions ------------------------------------------------

    @Test
    fun contentChangedUsesTheHashAloneWhenItDiffers() = runBlocking {
        val stored = entity(hash = "old")
        // The lazy full comparison must NOT be called on a hash mismatch —
        // that is the whole point of keeping it lazy.
        assertTrue(
            ImportDecision.contentChanged(stored, ownedElsewhere = false, hashMatches = false, incoming = dto()) {
                throw AssertionError("the options must not be read back on a hash mismatch")
            }
        )
    }

    @Test
    fun contentChangedDelegatesToTheFullComparisonWhenTheHashMatches() = runBlocking {
        val stored = entity(hash = "same")
        assertTrue(
            ImportDecision.contentChanged(stored, false, true, dto()) { true }
        )
        assertFalse(
            ImportDecision.contentChanged(stored, false, true, dto()) { false }
        )
    }

    @Test
    fun contentChangedIsFalseForAMissingOrForeignOwnedRow() = runBlocking {
        assertFalse(
            ImportDecision.contentChanged(null, false, false, dto()) {
                throw AssertionError("no row, no comparison")
            }
        )
        assertFalse(
            ImportDecision.contentChanged(entity(), true, false, dto()) {
                throw AssertionError("a foreign-owned row is never updated in place")
            }
        )
    }

    @Test
    fun refreshDecisionsAreSharedAndPurityChecked() {
        val stored = candidate()
        assertFalse(ImportDecision.needsAnswerRefresh(stored, dto()))
        assertTrue(ImportDecision.needsAnswerRefresh(stored, dto(correct = "b")))
        assertFalse(ImportDecision.refreshInvalidatesSchedule(stored, dto()))
        // A changed key is card content for a refreshed duplicate, so its
        // schedule must be reset even though the prompt matched by hash.
        assertTrue(ImportDecision.refreshInvalidatesSchedule(stored, dto(correct = "b")))
    }

    @Test
    fun scheduleForUsesVersionTwoPrecedenceAndVersionOneFallback() {
        val composite = cardScheduleKey("p1", "q1")
        val schedules = mapOf(
            composite to CardScheduleDto(dueAt = 2),
            "q1" to CardScheduleDto(dueAt = 1)
        )
        assertEquals(2L, ImportDecision.scheduleFor(schedules, "p1", "q1", 2)!!.dueAt)
        assertEquals(1L, ImportDecision.scheduleFor(schedules, "p1", "q1", 1)!!.dueAt)
        // A v2 file's bare alias still resolves for an old reader's paper id;
        // only a question id that is absent under both keys is a miss.
        assertEquals(1L, ImportDecision.scheduleFor(schedules, "pX", "q1", 2)!!.dueAt)
        assertNull(ImportDecision.scheduleFor(schedules, "p1", "missing", 2))
    }

    @Test
    fun paperSkipAndCountsAreDecidedInOnePlace() {
        assertTrue(ImportDecision.allDuplicatesPaperSkip(true, false, true))
        assertFalse(ImportDecision.allDuplicatesPaperSkip(true, false, false))
        assertFalse(ImportDecision.allDuplicatesPaperSkip(false, false, true))
        assertEquals(0 to 0, ImportDecision.countPaper(true, true))
        assertEquals(1 to 0, ImportDecision.countPaper(true, false))
        assertEquals(0 to 1, ImportDecision.countPaper(false, false))
    }
}
