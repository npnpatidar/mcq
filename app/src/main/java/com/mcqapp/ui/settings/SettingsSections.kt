package com.mcqapp.ui.settings

import com.mcqapp.domain.StorageInfo

/**
 * The five collapsible Settings groups, in display order.
 *
 * The ids are the section testTags (`settings-section-<id>`) and the storage
 * preview limit is the one place the collapsed per-paper list's cap lives, so
 * the UI and the JVM tests share one definition instead of two literals.
 */
const val STORAGE_PAPER_PREVIEW_LIMIT = 10

enum class SettingsSection(val id: String) {
    APPEARANCE("appearance"),
    TESTS("tests"),
    STUDY("study"),
    BACKUP_DATA("backup_data"),
    ABOUT("about")
}

fun settingsSectionsInOrder(): List<SettingsSection> = SettingsSection.entries.toList()

/**
 * Which section owns a preference, by its DataStore key name in
 * RepositorySettings. The moves pinned here are the reorganisation itself:
 * `update_answers_on_duplicate` left Tests for Backup & data, `pdf_two_column`
 * left its PDF card for Backup & data, and `load_remote_images` / `haptics_enabled`
 * live in Appearance.
 */
fun sectionForSettingKey(key: String): SettingsSection? = when (key) {
    "theme_mode", "theme_palette", "custom_themes", "font_scale",
    "load_remote_images", "haptics_enabled" -> SettingsSection.APPEARANCE
    "shuffle_questions", "shuffle_options", "practice_mode",
    "strict_mode", "auto_advance", "avoid_recent_questions" -> SettingsSection.TESTS
    "simplified_study", "scheduler_config" -> SettingsSection.STUDY
    "update_answers_on_duplicate", "pdf_two_column",
    "storage", "export", "backup", "restore" -> SettingsSection.BACKUP_DATA
    "about" -> SettingsSection.ABOUT
    else -> null
}

/** The rows the storage card shows: the full list expanded, the preview otherwise. */
fun storageVisiblePapers(
    perPaper: List<StorageInfo.PaperUsage>,
    showAll: Boolean
): List<StorageInfo.PaperUsage> =
    if (showAll) perPaper else perPaper.take(STORAGE_PAPER_PREVIEW_LIMIT)

/** Whether the collapsed list is actually hiding rows behind the expand toggle. */
fun shouldShowStorageExpandToggle(total: Int): Boolean =
    total > STORAGE_PAPER_PREVIEW_LIMIT
