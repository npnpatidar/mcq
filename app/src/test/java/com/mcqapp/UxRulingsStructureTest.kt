package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The navigation wiring and control scaffolding the pure tests cannot render.
 * Follows the same structural style as [StatsPageStructureTest]: a dropped
 * route, drawer row, test tag or string would compile and ship a feature
 * nobody can open.
 */
class UxRulingsStructureTest {

    private fun source(path: String): String = File("src/main/$path").readText()
    private fun strings(path: String): Set<String> =
        // Tolerate attributes such as tools:ignore="PluralsCandidate".
        Regex("""<string name="([a-z0-9_]+)"[^>]*>""")
            .findAll(File("src/main/res/$path").readText())
            .map { it.groupValues[1] }.toSet()

    @Test
    fun theAiSchemaScreenIsReachableFromSettingsAndTheDrawer() {
        val nav = source("java/com/mcqapp/ui/navigation/McqNavHost.kt")
        assertTrue(nav.contains("""composable("ai-schemas")"""))
        assertTrue(nav.contains("AiSchemaScreen"))

        val drawer = source("java/com/mcqapp/ui/library/LibraryDrawer.kt")
        assertTrue("drawer callback missing", drawer.contains("onOpenAiSchemas"))
        assertTrue("drawer tag missing", drawer.contains("drawer-ai-schemas"))
        assertTrue("drawer label missing", drawer.contains("R.string.ai_schemas"))

        val library = source("java/com/mcqapp/ui/library/LibraryScreen.kt")
        assertTrue("drawer not wired", library.contains("""navigate("ai-schemas")"""))

        val settings = source("java/com/mcqapp/ui/settings/SettingsScreen.kt")
        assertTrue("Settings card missing", settings.contains("settings-section-ai-schema"))
        assertTrue("Settings shortcut missing", settings.contains("settings-open-ai-schemas"))
        assertTrue("Settings route missing", settings.contains("""navigate("ai-schemas")"""))
    }

    @Test
    fun theDailyGoalControlsAreInTheStudySection() {
        val section = source("java/com/mcqapp/ui/settings/StudySchedulerSection.kt")
        assertTrue("switch tag missing", section.contains("setting-daily-goal\""))
        assertTrue("count stepper tag missing", section.contains("setting-daily-goal-count"))
        assertTrue("switch copy missing", section.contains("R.string.daily_goal_title"))
        assertTrue("count copy missing", section.contains("R.string.daily_goal_count_label"))
        assertTrue("stepper range missing", section.contains("DailyGoal.MIN_COUNT"))
        assertTrue("stepper is gated by the switch", section.contains("if (dailyGoalEnabled)"))

        val settings = source("java/com/mcqapp/ui/settings/SettingsScreen.kt")
        assertTrue("switch state not collected", settings.contains("viewModel.dailyGoalEnabled"))
        assertTrue("count state not collected", settings.contains("viewModel.dailyGoalCount"))
    }

    @Test
    fun bothNewStringSetsAreCompleteAndIsolated() {
        val en = strings("values/strings_daily_goal.xml") + strings("values/strings_ai_schema.xml")
        val hi = strings("values-hi/strings_hi_daily_goal.xml") +
            strings("values-hi/strings_hi_ai_schema.xml")
        val core = strings("values/strings.xml")
        assertTrue("English copy missing: ${expectedDaily - en}", expectedDaily.all { it in en })
        assertTrue("Hindi copy missing: ${expectedDaily - hi}", expectedDaily.all { it in hi })
        assertTrue("AI copy missing: ${expectedAi - en}", expectedAi.all { it in en })
        assertTrue("AI Hindi copy missing: ${expectedAi - hi}", expectedAi.all { it in hi })
        assertEquals("both locales declare the same keys", en, hi)
        assertFalse(
            "new keys must not be added to strings.xml",
            (expectedDaily + expectedAi).any { it in core }
        )
        assertTrue(
            "size label explains the trade-off",
            File("src/main/res/values/strings_ai_schema.xml").readText()
                .contains("smaller = fewer tokens")
        )
    }

    @Test
    fun goalCountIsGlobalInBothLibraryAndStatistics() {
        val library = source("java/com/mcqapp/ui/library/LibraryViewModel.kt")
        val stats = source("java/com/mcqapp/ui/stats/StatsViewModel.kt")
        assertTrue("Library must use the shared unscoped read", "DailyGoal.todayReviews(" in library)
        assertTrue("Statistics must use the shared unscoped read", "DailyGoal.todayReviews(" in stats)
        assertFalse(
            "the goal must not reuse the paper-filtered overview slice",
            "_state.map { it.overview?.today?.reviews" in stats
        )
        assertTrue("Statistics refreshes the goal on resume", "refreshDailyGoal()" in stats)
    }

    @Test
    fun goalPreferencesAreOwnedByTheStudySettingsSection() {
        assertEquals(
            com.mcqapp.ui.settings.SettingsSection.STUDY,
            com.mcqapp.ui.settings.sectionForSettingKey("daily_goal_enabled")
        )
        assertEquals(
            com.mcqapp.ui.settings.SettingsSection.STUDY,
            com.mcqapp.ui.settings.sectionForSettingKey("daily_goal_count")
        )
        assertEquals(
            "confirm_grade_before_next is rendered in the study card too",
            com.mcqapp.ui.settings.SettingsSection.STUDY,
            com.mcqapp.ui.settings.sectionForSettingKey("confirm_grade_before_next")
        )
    }

    @Test
    fun goalPreferencesDefaultOffAndTwentyInTheStore() {
        val prefs = source("java/com/mcqapp/data/repository/RepositorySettings.kt")
        assertTrue("enabled preference missing", prefs.contains("booleanPreferencesKey(\"daily_goal_enabled\")"))
        assertTrue("count preference missing", prefs.contains("intPreferencesKey(\"daily_goal_count\")"))
        assertTrue(prefs.contains("?: DailyGoal.DEFAULT_ENABLED"))
        assertTrue(prefs.contains("?: DailyGoal.DEFAULT_COUNT"))
        // Both types are covered by the generic snapshot/restore, so no
        // special-case branch is needed (and none should be added).
        assertTrue(prefs.contains("is Boolean -> McqBackupFormat.PreferenceEntry"))
        assertTrue(prefs.contains("is Int -> McqBackupFormat.PreferenceEntry"))
    }

    @Test
    fun theGoalIsNeverConsultedByStudyOrTestCode() {
        val study = File("src/main/java/com/mcqapp/ui/study").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("Study must not read the goal", "dailyGoal" in study || "DailyGoal" in study)
        val library = source("java/com/mcqapp/ui/library/LibraryScreen.kt")
        assertTrue("Library shows the gauge", "DailyGoalIndicator" in library)
        assertTrue("the library list still offers Study", "studyRoute" in library)
    }

    @Test
    fun allGeneratorControlsAndActionsHaveHooks() {
        val screen = source("java/com/mcqapp/ui/settings/AiSchemaScreen.kt")
        listOf(
            "ai-schema-kind", "ai-schema-blocks", "ai-schema-block-table",
            "ai-schema-block-math", "ai-schema-block-image", "ai-schema-passage",
            "ai-schema-containers", "ai-schema-include-papers",
            "ai-schema-include-categories", "ai-schema-include-passages",
            "ai-schema-char-count", "ai-schema-output-schema", "ai-schema-output-prompt",
            "ai-schema-output-example", "ai-schema-copy-prompt", "ai-schema-save",
            "ai-schema-share"
        ).forEach { tag -> assertTrue("missing hook $tag", screen.contains(tag)) }
        assertTrue("Copy prompt uses the clipboard", "ClipboardManager" in screen)
        assertTrue("Save uses a SAF document", "CreateDocument" in screen)
        assertTrue("Share uses the existing helper", "shareIntent" in screen)
        assertFalse(
            "the old wrapper control would conflict with the container toggles",
            screen.contains("ai-schema-wrapper-full")
        )
        assertTrue("container toggles must be checkboxes", screen.contains("Checkbox("))
        listOf("includePapers", "includeCategories", "includePassages").forEach { field ->
            assertTrue(
                "the toggle $field must reach the generator",
                screen.contains("$field = $field")
            )
        }
    }

    @Test
    fun aiSchemaActionsUseTwoFullWidthRowsOnNarrowPhones() {
        val screen = source("java/com/mcqapp/ui/settings/AiSchemaScreen.kt")
        val at = screen.indexOf("ai-schema-copy-prompt")
        assertTrue("Copy prompt action missing", at >= 0)
        val actions = screen.substring(
            screen.lastIndexOf("Column(", at),
            screen.indexOf("ai-schema-share", at) + 120
        )
        assertTrue("the actions need their own column", actions.startsWith("Column("))
        assertTrue("Copy prompt and Save schema share the first row", actions.contains("Row("))
        assertTrue(
            "both first-row buttons must split the width",
            Regex("""weight\(1f\)""").findAll(actions).count() == 2
        )
        assertTrue("Share must span its own row", actions.contains("fillMaxWidth()"))
        assertTrue(
            "Share stays the last action",
            actions.indexOf("ai-schema-share") > actions.indexOf("ai-schema-save")
        )
    }

    private val expectedDaily = setOf(
        "daily_goal_title", "daily_goal_switch_help", "daily_goal_count_label",
        "daily_goal_count_help", "daily_goal_progress", "daily_goal_progress_help"
    )

    private val expectedAi = setOf(
        "ai_schemas", "ai_schema_settings_title", "ai_schema_settings_help", "ai_schema_open",
        "ai_schema_question_type", "ai_schema_kind_flashcards", "ai_schema_kind_mcq",
        "ai_schema_kind_mixed", "ai_schema_blocks", "ai_schema_block_text_always",
        "ai_schema_block_table", "ai_schema_block_math", "ai_schema_block_image",
        "ai_schema_passage", "ai_schema_passage_none", "ai_schema_passage_text",
        "ai_schema_passage_rich", "ai_schema_wrapper", "ai_schema_wrapper_full",
        "ai_schema_wrapper_paperless", "ai_schema_output_schema", "ai_schema_output_prompt",
        "ai_schema_output_example", "ai_schema_size", "ai_schema_copy_prompt", "ai_schema_save",
        "ai_schema_share", "ai_schema_copied", "ai_schema_saved", "ai_schema_save_failed",
        "ai_schema_share_no_app", "ai_schema_share_file_name"
    )
}
