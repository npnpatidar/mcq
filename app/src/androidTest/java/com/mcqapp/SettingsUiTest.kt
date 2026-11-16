package com.mcqapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.navigation.McqNavHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings reorganisation on the CI emulator: the drawer reaches
 * Settings, a switch row toggles and persists, and the Advanced Anki block
 * still expands behind its tag. The JVM suite cannot drive Compose, so these
 * run instrumented. The section grouping itself is pinned by the pure
 * SettingsSectionsTest.
 */
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private var originalLoadRemoteImages: Boolean = false

    @After
    fun restoreSettings() = runBlocking {
        repository.setLoadRemoteImages(originalLoadRemoteImages)
        repository.resetSchedulerConfig()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSettings() {
        originalLoadRemoteImages = runBlocking { repository.loadRemoteImages().first() }
        compose.setContent { McqNavHost(repository = repository) }
        waitFor("Settings")
        compose.onNodeWithContentDescription("Open menu").performClick()
        waitFor("Settings")
        // The drawer's Settings row is the only "Settings" text on the
        // library screen (the title is "MCQ App").
        compose.onNodeWithText("Settings", substring = false).performClick()
        waitForTag("settings-section-appearance")
        // The scheduler section shows a loading row until the stored config
        // (and the seeded rule) arrives; its advanced block only exists once
        // the section is interactive, so every scroll below must wait for it.
        waitForTag("advanced-anki-settings")
    }

    @Test
    fun drawerReachesSettingsAndSectionsAreVisible() {
        openSettings()
        // The screen scrolls, so the later sections need a scroll first.
        listOf(
            "settings-section-appearance",
            "settings-section-tests",
            "settings-section-study",
            "settings-section-backup-data",
            "settings-section-about"
        ).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        // The theme picker and import controls survive the move.
        compose.onNodeWithTag("theme-picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("theme-import").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun togglingLoadRemoteImagesPersists() {
        openSettings()
        val before = originalLoadRemoteImages
        compose.onNodeWithTag("setting-load-remote-images").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.loadRemoteImages().first() } != before
        }
        val after = runBlocking { repository.loadRemoteImages().first() }
        org.junit.Assert.assertEquals(
            "the toggle must flip the stored setting",
            !before, after
        )
    }

    @Test
    fun advancedAnkiBlockExpandsBehindItsTag() {
        openSettings()
        // The advanced knobs are collapsed until the tag is tapped.
        compose.onNodeWithTag("advanced-anki-settings").performScrollTo()
        // The leech threshold row is only in the tree when expanded.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Maximum interval", substring = true)
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("advanced-anki-settings").performClick()
        waitFor("Maximum interval")
        // Expanding below the fold does not move the viewport; both rows are
        // composed but not visible until scrolled to.
        compose.onNodeWithText("Maximum interval", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Next day starts at", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
