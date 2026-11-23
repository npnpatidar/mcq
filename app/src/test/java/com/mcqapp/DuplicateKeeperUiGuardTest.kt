package com.mcqapp

import com.mcqapp.ui.browse.duplicateCandidatePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INCIDENT: every keeper chip in Browse's duplicate dialog said "Keep this one",
 * only the first two candidates were previewed, and the merge action did not
 * say which card it would keep. Candidates are now numbered, named in their
 * accessibility label, and the keeper is named in the merge action.
 */
class DuplicateKeeperUiGuardTest {

    @Test
    fun aBlankPreviewFallsBackToTheIdAndLongPreviewsAreShortened() {
        assertEquals("q-7", duplicateCandidatePreview(null, "q-7"))
        assertEquals("q-7", duplicateCandidatePreview("   ", "q-7"))
        assertEquals("Physics Q", duplicateCandidatePreview("  Physics Q  ", "q-7"))
        val long = "A".repeat(40)
        val short = duplicateCandidatePreview(long, "q-7")
        assertEquals(24, short.length)
        assertTrue(short.endsWith("…"))
        assertEquals("A".repeat(23), short.dropLast(1))
    }

    @Test
    fun theDialogNumbersEveryChipAndNamesTheKeeperInTheMergeAction() {
        val screen = File("src/main/java/com/mcqapp/ui/browse/BrowseScreen.kt").readText()
        assertTrue(
            "every candidate must be numbered, not just the first two previewed",
            screen.contains("group.questionIds.forEachIndexed { index, id ->")
        )
        assertTrue(screen.contains("R.string.duplicates_keep_numbered"))
        assertTrue(screen.contains("R.string.duplicates_keep_candidate"))
        assertTrue(
            "each chip needs a distinct accessibility label",
            screen.contains("contentDescription = description")
        )
        assertTrue(screen.contains("R.string.duplicates_merge_history_into"))
        assertTrue(
            "the merge action must name the chosen keeper",
            screen.contains("val keeperPreview = duplicateCandidatePreview(") &&
                screen.contains("duplicatePreviews[keeper], keeper")
        )
        assertFalse(
            "the identical-label chips are gone",
            screen.contains("label = { Text(stringResource(R.string.duplicates_keep_one)) }")
        )
    }

    @Test
    fun theNewCopyIsMirrorTranslatedWithMatchingPlaceholders() {
        fun resources(path: String): Map<String, String> =
            Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(File(path).readText())
                .associate { it.groupValues[1] to it.groupValues[2].trim() }

        val en = resources("src/main/res/values/strings_library_scale.xml")
        val hi = resources("src/main/res/values-hi/strings_library_scale_hi.xml")
        assertEquals("the two locales must mirror each other", en.keys, hi.keys)
        assertTrue(en.keys.containsAll(listOf(
            "duplicates_keep_numbered", "duplicates_keep_candidate", "duplicates_merge_history_into"
        )))
        val format = { value: String -> Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toSet() }
        for (name in listOf("duplicates_keep_numbered", "duplicates_keep_candidate", "duplicates_merge_history_into")) {
            assertTrue("$name is blank in English", en.getValue(name).isNotBlank())
            assertTrue("$name is blank in Hindi", hi.getValue(name).isNotBlank())
            assertEquals("format specifiers differ for $name", format(en.getValue(name)), format(hi.getValue(name)))
            assertNotEquals("$name is not translated", en.getValue(name), hi.getValue(name))
        }
    }
}
