package com.mcqapp

import com.mcqapp.domain.SchedulerConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIGH wiring gap: [com.mcqapp.data.anki.AnkiPackageReader.read] and
 * [com.mcqapp.data.anki.AnkiPackageReader.stream] accept the learner's
 * configured `leechThreshold`, but the production importer used to leave the
 * parameter at its default, so a learner who set a custom lapse count still got
 * Anki's historical 8 on import. This guards the production seam: the importer
 * must hand its configured [SchedulerConfig] threshold to the streamed reader,
 * and the library screen must hand the learner's config to the importer.
 */
class Round2ImportLeechThresholdWiringTest {

    private val importerSource =
        File("src/main/java/com/mcqapp/data/io/AnkiApkgImporter.kt").readText()
    private val libraryVmSource =
        File("src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt").readText()

    @Test
    fun importerPassesTheConfiguredThresholdToTheStreamedReader() {
        assertTrue(
            "AnkiApkgImporter must stream the .apkg through AnkiPackageReader.stream",
            importerSource.contains("AnkiPackageReader.stream(")
        )
        assertTrue(
            "the stream call must forward schedulerConfig.leechThreshold",
            importerSource.contains("leechThreshold = schedulerConfig.leechThreshold")
        )
        // It must reuse the very same config object it uses for FSRS parameters.
        assertTrue(
            "the same configured config must feed FSRS parameters",
            importerSource.contains("parameters = schedulerConfig.fsrsParameters")
        )
    }

    @Test
    fun importerDoesNotHardcodeTheDefaultThreshold() {
        // Negative: falling back to the reader default at the call site is the
        // original bug, so neither a literal nor Anki's constant may appear.
        assertFalse(
            "the importer must not hardcode a leech threshold constant",
            importerSource.contains("AnkiScheduling.LEECH_THRESHOLD")
        )
        assertFalse(
            "the importer must not pass a literal threshold",
            Regex("""leechThreshold\s*=\s*\d+""").containsMatchIn(importerSource)
        )
    }

    @Test
    fun productionCallerSuppliesTheLearnersConfigToTheImporter() {
        assertTrue(
            "LibraryViewModel must pass the learner's scheduler config to AnkiApkgImporter",
            libraryVmSource.contains("schedulerConfig = repository.schedulerConfigNow()")
        )
    }

    @Test
    fun configuredThresholdSurvivesOnTheConfigObject() {
        // The value flowing through the wiring is the learner's, not a fixed one.
        assertEquals(3, SchedulerConfig(leechThreshold = 3).leechThreshold)
        assertTrue("the default must still be a usable threshold", SchedulerConfig().leechThreshold >= 1)
    }
}
