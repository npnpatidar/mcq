package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parent-category picker renders one radio row per category. The dialog
 * clips whatever is taller than its max height, so an unscrollable list
 * simply hides the lower categories (the edit dialog shipped exactly that
 * way). The fix is structural, so it is checked structurally.
 */
class LibraryDialogScrollTest {

    private val source =
        File("src/main/java/com/mcqapp/ui/library/LibraryDialogs.kt").readText()

    @Test
    fun theParentPickerScrollsInsideTheDialog() {
        assertTrue(
            "the parent picker must scroll",
            source.contains("verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "the picker must stay inside a bounded height",
            source.contains("heightIn(max = ")
        )
    }

    @Test
    fun bothCategoryDialogsUseTheScrollablePicker() {
        // Two call sites (edit + new) plus the definition itself.
        assertEquals(
            "both dialogs must go through ParentPicker",
            3,
            Regex("""ParentPicker\(""").findAll(source).count()
        )
    }
}
