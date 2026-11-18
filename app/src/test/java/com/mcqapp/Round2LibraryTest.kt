package com.mcqapp

import com.mcqapp.R
import com.mcqapp.domain.TestType
import com.mcqapp.ui.library.SampleLoadGate
import com.mcqapp.ui.library.blueprintDismissAllowed
import com.mcqapp.ui.library.fillSourceLabelRes
import com.mcqapp.ui.library.paperSummaryInputs
import com.mcqapp.ui.library.rowsAfterDelete
import com.mcqapp.ui.library.testRouteCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-2 library fixes. The behaviour that caused the findings is put behind
 * pure seams so the decisive positive/negative cases run on the ARM64 host;
 * the Compose-only wiring is checked structurally. No Robolectric or new test
 * dependency is involved.
 */
class Round2LibraryTest {

    // ---- L2: Test-Start route is built in the ViewModel scope ----

    @Test
    fun blueprintStartUsesItsSectionUnion() {
        val union = listOf("c1", "c2")
        assertEquals(
            union,
            testRouteCategories(TestType.BLUEPRINT, union, listOf("ticked"))
        )
    }

    @Test
    fun nonBlueprintAndMissingBlueprintFallBackToSheetScope() {
        val scope = listOf("ticked")
        assertEquals(scope, testRouteCategories(TestType.BLUEPRINT, null, scope))
        assertEquals(scope, testRouteCategories(TestType.RANDOM, listOf("c1"), scope))
        assertEquals(scope, testRouteCategories(TestType.MISTAKES, listOf("c1"), scope))
        // A blueprint's own (possibly empty) union is intentional: its scope is
        // the plan, not the sheet's ticked branches.
        assertTrue(testRouteCategories(TestType.BLUEPRINT, emptyList(), scope).isEmpty())
    }

    // ---- L3: a save in flight cannot be dismissed ----

    @Test
    fun blueprintSheetBlocksDismissalOnlyWhileSaving() {
        assertTrue(blueprintDismissAllowed(false))
        assertFalse(blueprintDismissAllowed(true))
    }

    // ---- L4: distinct fill labels ----

    @Test
    fun authoredAndBlueprintFillsGetFourDistinctLabels() {
        val labels = listOf(
            fillSourceLabelRes("AUTHORED_HARD"),
            fillSourceLabelRes("AUTHORED_MEDIUM"),
            fillSourceLabelRes("BLUEPRINT"),
            fillSourceLabelRes("BLUEPRINT_FILL")
        )
        assertEquals("four labels must be distinct", 4, labels.toSet().size)
        assertFalse(R.string.test_setup_fill_recent in labels)
        assertFalse(R.string.test_setup_fill_random in labels)
        assertEquals(R.string.r2_lib_fill_authored_hard, labels[0])
        assertEquals(R.string.r2_lib_fill_authored_medium, labels[1])
        assertEquals(R.string.r2_lib_fill_blueprint, labels[2])
        assertEquals(R.string.r2_lib_fill_blueprint_fill, labels[3])
        // The still-correct mappings were not weakened.
        assertEquals(R.string.test_setup_fill_recent, fillSourceLabelRes("LEAST_RECENTLY_REVIEWED"))
        assertEquals(R.string.test_setup_fill_random, fillSourceLabelRes("SOMETHING_NEW"))
    }

    // ---- L7: failed deletes keep their row ----

    @Test
    fun failedDeleteKeepsTheRowSuccessfulDeleteRemovesIt() {
        val rows = listOf(1L, 2L, 3L)
        assertEquals(listOf(1L, 3L), rowsAfterDelete(Result.success(Unit), rows, { it }, 2L))
        assertEquals(rows, rowsAfterDelete(Result.failure(IllegalStateException("locked")), rows, { it }, 2L))
    }

    // ---- L8: localized summary + guarded sample load ----

    @Test
    fun paperSummaryHidesZeroTermsAndKeepsRealOnes() {
        val none = paperSummaryInputs(0, 0, 0.0)
        assertEquals(0, none.questions)
        assertNull(none.minutes)
        assertNull(none.negative)

        val full = paperSummaryInputs(12, 45, 0.33)
        assertEquals(12, full.questions)
        assertEquals(45, full.minutes!!)
        assertEquals(0.33, full.negative!!, 0.0)

        val untimed = paperSummaryInputs(5, -1, -0.5)
        assertNull(untimed.minutes)
        assertNull(untimed.negative)
    }

    @Test
    fun sampleLoadGateRejectsAConcurrentSecondTapThenAllowsRetry() {
        val gate = SampleLoadGate()
        assertTrue(gate.tryStart())
        assertTrue(gate.isInFlight)
        assertFalse(gate.tryStart())
        gate.finish()
        assertFalse(gate.isInFlight)
        assertTrue(gate.tryStart())
    }

    // ---- Both locales: matching names, specifiers, prefix ----

    private fun entries(path: String): Map<String, String> {
        val xml = File(path).readText()
        val strings = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = Regex("""<plurals name="([a-z0-9_]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { m ->
                m.groupValues[1] to Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .find(m.groupValues[2])?.groupValues?.get(1).orEmpty()
            }
        return strings + plurals
    }

    private val specifier = Regex("""%\d+\$[a-zA-Z]""")

    @Test
    fun everyRoundTwoStringIsDefinedInBothLocalesWithMatchingSpecifiers() {
        val en = entries("src/main/res/values/strings_round2_lib.xml")
        val hi = entries("src/main/res/values-hi/strings_round2_lib_hi.xml")
        assertTrue("English round-2 file is empty", en.size > 15)
        assertEquals("locale name sets differ: ${en.keys - hi.keys} / ${hi.keys - en.keys}",
            en.keys, hi.keys)
        en.forEach { (name, value) ->
            assertTrue("$name must use the r2_lib_ prefix", name.startsWith("r2_lib_"))
            assertEquals("$name specifiers differ between locales",
                specifier.findAll(value).map { it.value }.toList(),
                specifier.findAll(hi.getValue(name)).map { it.value }.toList())
        }
    }

    @Test
    fun backupRefusalIsLocalizedAndExportRowsUseFormatResourceIds() {
        val vm = File("src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt").readText()
        assertTrue("backup refusal must go through the localized message channel",
            vm.contains("msg(R.string.r2_lib_backup_not_bank)"))
        val hi = entries("src/main/res/values-hi/strings_round2_lib_hi.xml")
        assertTrue("Hindi refusal must be Hindi, not English",
            hi.getValue("r2_lib_backup_not_bank").contains("बैकअप"))
        assertFalse("the refusal must not stay English",
            "This is a library backup" in hi.getValue("r2_lib_backup_not_bank"))

        val screen = File("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt").readText()
        assertTrue("export title rows must resolve a resource id",
            screen.contains("stringResource(format.titleRes)"))
        assertTrue("export description rows must resolve a resource id",
            screen.contains("stringResource(format.descriptionRes)"))
        assertFalse("the English data-layer copy must not be rendered directly",
            "Text(format.title" in screen || "Text(format.description" in screen)
    }

    @Test
    fun accessibilityAndPristineFormWiringIsPresent() {
        val card = File("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt").readText()
        assertTrue("category title must expose a button role", card.contains("role = Role.Button"))
        assertTrue("category title must have an onclick label", card.contains("onClickLabel = openCategoryLabel"))
        assertTrue("menu and delete flags must survive rotation",
            card.contains("var menuOpen by rememberSaveable") &&
                card.contains("var confirmDelete by rememberSaveable"))
        assertFalse("the flags must no longer be plain remember",
            "var menuOpen by remember {" in card ||
                "var confirmDelete by remember {" in card)

        val editor = File("src/main/java/com/mcqapp/ui/library/BlueprintEditorSheet.kt").readText()
        assertTrue("steppers must be labelled",
            editor.contains("R.string.r2_lib_decrease_parts") &&
                editor.contains("R.string.r2_lib_increase_parts"))
        assertTrue("pristine-form errors are gated on interaction",
            editor.contains("formTouched && nameBlank") &&
                editor.contains("formTouched && noSections"))
        assertTrue("save and cancel are blocked while saving",
            editor.contains("blueprintDismissAllowed(saving)"))
    }
}
