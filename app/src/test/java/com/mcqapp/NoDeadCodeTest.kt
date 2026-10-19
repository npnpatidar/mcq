package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against the dead code the audit found, and against the escapers
 * drifting apart again. Deleting a public function is invisible to the
 * compiler; leaving it behind is how four copies of the same escaper appeared.
 */
class NoDeadCodeTest {

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun sourceOf(name: String): String {
        val file = sources().firstOrNull { it.name == name }
        assertTrue("missing source $name", file != null)
        return file!!.readText()
    }

    @Test
    fun theUnreferencedHelpersAreGone() {
        listOf(
            "ImportViewModel.kt" to "fun loadJson(",
            "McqRepository.kt" to "fun searchQuestions(",
            "McqRepository.kt" to "fun observeQuestion(",
            "AnkiSchema11.kt" to "fun stripHtml(",
            "Scoring.kt" to "fun isGraded(",
            // The legacy id-list replay has one implementation now
            // (SessionOrder.reorderedTo, which guards a repeated id); the
            // test-only copy that did not guard is gone.
            "TestSnapshot.kt" to "fun reorder("
        ).forEach { (file, needle) ->
            assertFalse("$file still declares $needle", sourceOf(file).contains(needle))
        }
    }

    @Test
    fun theDeadCountDaosAreGone() {
        val daos = sourceOf("Daos.kt")
        for (needle in listOf("fun countDue(", "fun countLeeches(", "fun countNew(", "fun countByCategory(")) {
            assertFalse("Daos.kt still declares $needle", daos.contains(needle))
        }
    }

    /**
     * `getStudyQueue(leechesOnly = false)` materialises every eligible card, so
     * it is linear in the paper — a million cards on a paper nobody has studied.
     * It stays because it is the study tests' oracle: it is what "the badge and
     * the session agree" is asserted against, and what daily caps were removed
     * against. No screen may call it. The session pulls one card at a time
     * (`getNextStudyCard`), and the drill asks for leeches (`leechesOnly = true`,
     * bounded by the schedules on file).
     */
    @Test
    fun theLinearStudyQueueHasNoProductionCaller() {
        // A 200-character window covers a call whose arguments wrap.
        val window = 200
        val offenders = mutableListOf<String>()
        sources()
            .filter { it.name != "McqRepository.kt" && it.name != "StudyStore.kt" }
            .forEach { file ->
                val text = file.readText()
                var at = text.indexOf("getStudyQueue(")
                while (at >= 0) {
                    val call = text.substring(at, minOf(text.length, at + window))
                    if (!call.contains("leechesOnly = true")) {
                        val line = text.count { it == '\n' } + 1
                        offenders += "${file.name}:$line"
                    }
                    at = text.indexOf("getStudyQueue(", at + 1)
                }
            }
        assertTrue(
            "these ask for the linear full study queue: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Two readers materialise a whole table: every result row of an attempt and
     * every bookmarked question. Both screens page instead
     * (`resultPagingSource`, `bookmarkedPagingSource`), so they stay as the
     * tests' oracles — the list a streamed save is asserted against, and the
     * provenance a bookmark is asserted against — and no production path may
     * ask for them.
     */
    @Test
    fun theWholeTableReadersHaveNoProductionCaller() {
        val offenders = mutableListOf<String>()
        sources()
            .filter { it.name != "McqRepository.kt" && it.name != "HistoryStore.kt" }
            .forEach { file ->
                val text = file.readText()
                for (needle in listOf("getBookmarkedQuestions()", "getAttemptResults(")) {
                    if (text.contains(needle)) offenders += "${file.name}:$needle"
                }
            }
        assertTrue("these read a whole table: $offenders", offenders.isEmpty())
    }

    /**
     * `getLatestStandings` materialises one standing per question ever graded in
     * the whole bank; `getBookmarkExportDto` builds every bookmark as one DTO.
     * Both are linear in the bank. They stay as the tests' oracles — the
     * mistake order and the bookmark grouping are asserted against them — and
     * no production path may call them: the badges count in SQL
     * (`countMistakesByPaper`), the mistakes session asks for one paper
     * (`getMistakenQuestionIdsForPaper`), and the export walks pages
     * (`Exporter.bookmarkStream`).
     */
    @Test
    fun theStandingAndBookmarkOraclesHaveNoProductionCaller() {
        val offenders = mutableListOf<String>()
        sources()
            .filter { it.name !in setOf("Daos.kt", "McqRepository.kt", "HistoryStore.kt") }
            .forEach { file ->
                val text = file.readText()
                for (needle in listOf("getLatestStandings()", "getBookmarkExportDto()")) {
                    if (text.contains(needle)) offenders += "${file.name}:$needle"
                }
            }
        assertTrue("these read the whole bank: $offenders", offenders.isEmpty())
    }

    /**
     * `AnkiPackageReader.read` expands the whole archive into memory as a
     * `Map<String, ByteArray>`, inlines every picture into one DTO for the whole
     * bank, and hands that to a whole-file importer. `.apkg` now streams
     * (`AnkiApkgImporter` -> `AnkiPackageReader.stream`), so it stays as the
     * equivalence test's oracle — the database the streamed import is asserted
     * against — and no production file may call it.
     */
    @Test
    fun theWholeFileAnkiReaderHasNoProductionCaller() {
        val offenders = mutableListOf<String>()
        sources()
            .filter { it.name != "AnkiPackageReader.kt" }
            .forEach { file ->
                val text = file.readText()
                for (needle in listOf("AnkiPackageReader.read(", "packageReader.read(")) {
                    if (text.contains(needle)) offenders += "${file.name}:$needle"
                }
            }
        assertTrue("these read the whole .apkg: $offenders", offenders.isEmpty())
    }

    @Test
    fun imageScalingHasOneImplementation() {
        // The paper-level scaler is the single copy of the mapping; the
        // file-level scaler delegates to it so there is no second mapping,
        // and the import path scales one paper at a time.
        val importer = sourceOf("Importer.kt")
        assertTrue(
            "the file-level scaler delegates to the paper-level one",
            importer.contains("papers.map { it.withDownscaledImages() }")
        )
        assertTrue(
            "the import path scales one paper at a time",
            importer.contains("paperDto.withDownscaledImages()")
        )
    }

    @Test
    fun theStoredRowsComeFromTheScaledTwin() {
        // Scaling the DTO is only half: the row's element JSON is what
        // ContentHash.sameQuestionContent reads back, so a row built from the
        // file's original bytes reads as changed on every re-import - and the
        // full-size picture stays stored. The row builders are the one
        // construction site each; both write paths hand them the twin.
        val importer = sourceOf("Importer.kt")
        val streaming = sourceOf("StreamingImportSession.kt")
        val builders = listOf("QuestionEntity", "OptionEntity", "PassageEntity")
        for (builder in builders) {
            assertEquals(
                "$builder should be built in one place",
                1,
                constructions(importer, builder)
            )
            assertEquals(
                "$builder should not be built inline in the streaming path",
                0,
                constructions(streaming, builder)
            )
        }
        for (file in listOf("Importer.kt" to importer, "StreamingImportSession.kt" to streaming)) {
            val (name, text) = file
            assertTrue("$name stores the scaled question", text.contains("scaledQuestion.toQuestionEntity("))
            assertTrue("$name stores the scaled options", text.contains("scaledQuestion.toOptionEntities("))
            assertTrue(
                "$name stores the scaled passage",
                text.contains("passageDto.withDownscaledImages().toPassageEntity(")
            )
            assertTrue(
                "$name refreshes with the scaled explanation",
                text.contains("scaledQuestion.storedExplanationJson(json)")
            )
            // Presence alone would pass if an unscaled call were added
            // alongside the scaled one, so the file's DTO forms are banned.
            for (needle in listOf(
                "questionDto.toQuestionEntity(",
                "dto.toQuestionEntity(",
                "questionDto.toOptionEntities(",
                "dto.toOptionEntities(",
                "questionDto.storedExplanationJson(",
                "dto.storedExplanationJson(",
                "passageDto.toPassageEntity("
            )) {
                assertFalse(
                    "$name builds a row from the file's own DTO: $needle",
                    text.contains(needle)
                )
            }
        }
    }

    @Test
    fun theImportButtonGateHasOneImplementation() {
        // The gate and its labels live in a pure function so a JVM test can pin
        // them; the screen only renders the answer. Re-inlining a label would
        // put a second copy of the rules back in the composable, where no test
        // can see it - the same shape as the import store's second call site.
        val screen = sourceOf("ImportScreen.kt")
        assertTrue(
            "the screen renders the gate's answer",
            screen.contains("enabled = importAction.enabled")
        )
        assertTrue(
            "the screen renders the gate's label",
            screen.contains("Text(importAction.label)")
        )
        assertFalse(
            "the label text belongs to the gate, not the screen",
            screen.contains("Nothing new to import")
        )
    }

    @Test
    fun bothSessionClocksGoThroughTheSameGate() {
        // clockMayRun is the one rule: in view, not paused, answerable. The
        // pure test pins the rule, but it cannot see whether a view model asks
        // it - a call site that reverts to consulting only its own pause flag
        // leaves every SessionClockTest green, and its Robolectric wiring test
        // only runs on CI. These are the shapes both sessions used to resume
        // past visibility; they must stay gone.
        val study = sourceOf("StudyViewModel.kt")
        val test = sourceOf("TestViewModel.kt")
        for (file in listOf("StudyViewModel.kt" to study, "TestViewModel.kt" to test)) {
            val (name, text) = file
            assertTrue("$name should gate on clockMayRun", text.contains("clockMayRun("))
            assertTrue(
                "$name should feed the gate the last visibility it was told",
                text.contains("questionInView = questionInView")
            )
        }
        // The one start that does not come through resumeQuestionClock: a fresh
        // card is clocked only if the screen last said the card is in view.
        assertTrue(
            "StudyViewModel.kt should clock a fresh card only while it is in view",
            study.contains("clockRunning = questionInView")
        )
        for (needle in listOf(
            // A resume that checks only the learner's pause, not visibility.
            "if (!clockPausedByUser) resumeQuestionClock()",
            "current.clockRunning || clockPausedByUser"
        )) {
            assertFalse("StudyViewModel.kt resumes past the gate: $needle", study.contains(needle))
        }
        for (needle in listOf(
            // The foreground and setQuestionVisible shapes that ignored a cover.
            "if (!_state.value.dwellPausedByUser) setDwellActive(true)",
            "visible && !_state.value.dwellPausedByUser"
        )) {
            assertFalse("TestViewModel.kt resumes past the gate: $needle", test.contains(needle))
        }
    }

    /** Constructions of [entity], not the `toXEntity(` calls that end in its name. */
    private fun constructions(text: String, entity: String): Int =
        Regex("(?<![A-Za-z])" + Regex.escape(entity) + "\\(").findAll(text).count()

    @Test
    fun allRenderersShareOneHtmlEscaper() {
        // escapeHtmlText is the single implementation; the Anki variant keeps
        // its own because it also maps ' and newlines for Anki's renderer.
        val richText = sourceOf("RichTextHtml.kt")
        assertTrue(richText.contains("internal fun escapeHtmlText("))
        assertTrue(
            "ContentElements should delegate",
            sourceOf("ContentElements.kt").contains("escapeHtmlText(s)")
        )
        assertTrue(
            "HtmlPaperWriter should delegate",
            sourceOf("HtmlPaperWriter.kt").contains("escapeHtmlText(s)")
        )
    }
}
