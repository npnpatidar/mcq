package com.mcqapp

import com.mcqapp.domain.StorageInfo
import com.mcqapp.ui.settings.STORAGE_PAPER_PREVIEW_LIMIT
import com.mcqapp.ui.settings.SettingsSection
import com.mcqapp.ui.settings.sectionForSettingKey
import com.mcqapp.ui.settings.settingsSectionsInOrder
import com.mcqapp.ui.settings.shouldShowStorageExpandToggle
import com.mcqapp.ui.settings.storageVisiblePapers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Settings reorganisation (Option B): the five collapsible sections
 * in order, the moved switches (`update_answers_on_duplicate` and
 * `pdf_two_column` to Backup & data), and the storage per-paper list preview.
 * Pure JVM like [SettingsFormatValueTest]; the Compose wiring is exercised
 * by the instrumented SettingsUiTest on CI.
 */
class SettingsSectionsTest {

    @Test
    fun sectionsAreInDisplayOrderWithAboutLast() {
        assertEquals(
            listOf(
                SettingsSection.APPEARANCE,
                SettingsSection.TESTS,
                SettingsSection.STUDY,
                SettingsSection.BACKUP_DATA,
                SettingsSection.ABOUT
            ),
            settingsSectionsInOrder()
        )
    }

    @Test
    fun movedSwitchesLiveInBackupAndData() {
        assertEquals(SettingsSection.BACKUP_DATA, sectionForSettingKey("update_answers_on_duplicate"))
        assertEquals(SettingsSection.BACKUP_DATA, sectionForSettingKey("pdf_two_column"))
        // And not in Tests.
        assertFalse(sectionForSettingKey("update_answers_on_duplicate") == SettingsSection.TESTS)
    }

    @Test
    fun appearanceHoldsThemeTextSizePrivacyAndFeedback() {
        for (key in listOf(
            "theme_mode", "theme_palette", "font_scale",
            "load_remote_images", "haptics_enabled"
        )) {
            assertEquals(SettingsSection.APPEARANCE, sectionForSettingKey(key))
        }
        for (key in listOf(
            "shuffle_questions", "shuffle_options", "practice_mode",
            "strict_mode", "auto_advance", "avoid_recent_questions"
        )) {
            assertEquals(SettingsSection.TESTS, sectionForSettingKey(key))
        }
        assertEquals(SettingsSection.STUDY, sectionForSettingKey("scheduler_config"))
        assertEquals(SettingsSection.STUDY, sectionForSettingKey("simplified_study"))
    }

    @Test
    fun collapsedStorageShowsOnlyThePreview() {
        val papers = (1..25).map { i ->
            StorageInfo.PaperUsage("p$i", "Paper $i", i, 100L * i)
        }
        val collapsed = storageVisiblePapers(papers, showAll = false)
        assertEquals(STORAGE_PAPER_PREVIEW_LIMIT, collapsed.size)
        assertEquals(papers.take(STORAGE_PAPER_PREVIEW_LIMIT), collapsed)
        val expanded = storageVisiblePapers(papers, showAll = true)
        assertEquals(papers, expanded)
    }

    @Test
    fun smallLibrariesShowEverythingWithoutToggle() {
        val papers = listOf(
            StorageInfo.PaperUsage("p1", "Paper 1", 1, 0L),
            StorageInfo.PaperUsage("p2", "Paper 2", 2, 0L)
        )
        assertEquals(papers, storageVisiblePapers(papers, showAll = false))
        assertFalse(shouldShowStorageExpandToggle(papers.size))
        val many = (1..STORAGE_PAPER_PREVIEW_LIMIT + 1).map { i ->
            StorageInfo.PaperUsage("p$i", "Paper $i", i, 0L)
        }
        assertTrue(shouldShowStorageExpandToggle(many.size))
        assertFalse(shouldShowStorageExpandToggle(STORAGE_PAPER_PREVIEW_LIMIT))
    }
}
