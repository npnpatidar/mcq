package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the two screenshot fixes. The Compose behaviour is
 * exercised on the CI emulator; these pin the exact modifier/control choices
 * that a layout assertion cannot distinguish (ellipsis vs. plain clipping,
 * icon button vs. a text button with the same spoken label).
 */
class TopBarTitleAndPassageActionTest {

    private fun source(path: String): String {
        val file = File("src/main/java/com/mcqapp/$path")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun browseTitleIsOneEllipsizedLineWithTheFullSpokenTitle() {
        val browse = source("ui/browse/BrowseScreen.kt")
        assertTrue(
            "TextOverflow must be imported",
            browse.contains("import androidx.compose.ui.text.style.TextOverflow")
        )
        val at = browse.indexOf("browse-title")
        assertTrue("the browse title needs a stable test tag", at >= 0)
        val title = browse.substring(
            browse.lastIndexOf("title = {", at),
            browse.indexOf("navigationIcon =", at)
        )
        assertTrue("title must be capped at one line", title.contains("maxLines = 1"))
        assertTrue("one line must ellipsize, not clip", title.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue("the spoken title must be the uncut one", title.contains("contentDescription = title"))
        assertTrue(
            "selection and paper names must share the same title value",
            title.contains("val title = if (selectionMode)")
        )
    }

    @Test
    fun testSessionPaperTitleUsesTheSameOneLineEllipsis() {
        val test = source("ui/test/TestSessionScreen.kt")
        val at = test.indexOf("test-paper-title")
        assertTrue("the test paper title needs a stable test tag", at >= 0)
        val title = test.substring(
            test.lastIndexOf("title = {", at),
            test.indexOf("navigationIcon =", at)
        )
        assertTrue(title.contains("maxLines = 1"))
        assertTrue("maxLines alone clips mid-word", title.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(title.contains("contentDescription = title"))
    }

    @Test
    fun passageUnassignIsACompactIconButtonWithTheOldConfirmation() {
        val passages = source("ui/editor/PassagesScreen.kt")
        val at = passages.indexOf("passage-unassign-")
        assertTrue("the unassign control needs a per-member test tag", at >= 0)
        val action = passages.substring(at - 500, at + 500)
        assertTrue("the control must be an IconButton", action.contains("IconButton("))
        assertTrue("LinkOff reads as unassign", action.contains("Icons.Default.LinkOff"))
        assertTrue("TalkBack must still say Remove from passage", action.contains("contentDescription"))
        assertTrue(action.contains("R.string.passage_unassign_member"))
        assertFalse("the wide text label must be gone", action.contains("TextButton("))
        assertFalse(
            "the old wide label must not be rendered",
            action.contains("Text(stringResource(R.string.passage_unassign_member))")
        )
        assertTrue("click must still reset cards", action.contains("memberResetCards(setOf(member.id))"))
        assertTrue("click must still open the dialog", action.contains("pendingUnassign = member.id to member.text"))
        assertTrue(
            "the confirmation dialog stays",
            passages.contains("viewModel.unassignMembers(setOf(target.first))")
        )
    }

    @Test
    fun theMemberStemStillGetsMostOfTheRowWidth() {
        val passages = source("ui/editor/PassagesScreen.kt")
        val at = passages.indexOf("member.text,")
        assertTrue("member stem missing", at >= 0)
        val stem = passages.substring(at, at + 500)
        assertTrue(stem.contains("maxLines = 2"))
        assertTrue(
            "the stem must ellipsize",
            stem.contains("overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis")
        )
        assertTrue("the stem must take the remaining row width", stem.contains("Modifier.weight(1f, fill = false)"))
    }

    @Test
    fun browseRowOffersDeleteNotTheCopyIconButBulkDuplicateStays() {
        val browse = source("ui/browse/BrowseScreen.kt")
        val at = browse.indexOf("browse-delete-")
        assertTrue("the row delete button needs a stable tag", at >= 0)
        val row = browse.substring(at - 200, at + 200)
        assertTrue("Delete must be the row action", row.contains("Icons.Default.Delete"))
        assertTrue(row.contains("R.string.delete_question"))
        assertFalse(
            "the per-question copy icon must be gone",
            browse.contains("Icons.Default.ContentCopy")
        )
        assertFalse("no per-row duplicate callback", browse.contains("onDuplicate"))
        assertTrue(
            "bulk duplicate stays in the selection overflow",
            browse.contains("viewModel.bulkDuplicate(selectedIds)")
        )
        assertTrue(
            "a row delete still goes through the one confirmation",
            browse.contains("pendingDeleteIds = BrowseDeleteConfirmation.request(")
        )
    }

    @Test
    fun theSpokenUnassignLabelStillExistsInBothLocales() {
        val english = File("src/main/res/values/strings_library_scale.xml").readText()
        val hindi = File("src/main/res/values-hi/strings_library_scale_hi.xml").readText()
        assertTrue(english.contains("""name="passage_unassign_member""""))
        assertTrue(hindi.contains("""name="passage_unassign_member""""))
    }
}
