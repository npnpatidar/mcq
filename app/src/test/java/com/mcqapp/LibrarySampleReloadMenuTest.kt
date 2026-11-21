package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library's top-bar overflow offers "Reload sample paper", wired to the
 * same loader the empty-state button uses. The sample was only loadable while
 * the library was empty, so a fix to the bundled asset could never reach an
 * existing sample paper.
 *
 * Pure JVM: it reads the screen and the two locale files, the way the other
 * resource guards do.
 */
class LibrarySampleReloadMenuTest {

    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt").readText()
    }
    private val english by lazy { File("src/main/res/values/strings.xml").readText() }
    private val hindi by lazy { File("src/main/res/values-hi/strings_hi_2.xml").readText() }

    @Test
    fun theTopBarOverflowOffersAReloadThatCallsTheSampleLoader() {
        assertTrue("the overflow button uses MoreVert", screen.contains("Icons.Default.MoreVert"))
        assertTrue("the menu is a DropdownMenu", screen.contains("DropdownMenu("))
        assertTrue(
            "the action calls the view model's sample loader",
            screen.contains("viewModel.loadSampleData()")
        )
        assertTrue(
            "the action is disabled while a sample load is in flight",
            screen.contains("enabled = !isLoadingSample")
        )
        // The empty-state button must survive: a first load has its own path.
        assertTrue(
            "the empty-state Load sample paper button is kept",
            screen.contains("R.string.load_sample_paper")
        )
    }

    @Test
    fun theActionLabelAndDescriptionAreLocalizedResourcesRenderedAsText() {
        assertTrue(
            "the label is a string resource inside Text",
            Regex("""Text\(stringResource\(R\.string\.reload_sample_paper\)\)""")
                .containsMatchIn(screen)
        )
        assertTrue(
            "the overflow icon has a resource content description",
            Regex("""contentDescription = stringResource\(R\.string\.library_more_actions\)""")
                .containsMatchIn(screen)
        )
        for (key in listOf("reload_sample_paper", "library_more_actions")) {
            assertTrue("$key must exist in English", english.contains("""name="$key""""))
            assertTrue("$key must exist in Hindi", hindi.contains("""name="$key""""))
        }
        // Plain action labels: no placeholder may creep in.
        assertFalse(
            Regex("""name="(reload_sample_paper|library_more_actions)"[^>]*>[^<]*%""")
                .containsMatchIn(english + hindi)
        )
    }
}
