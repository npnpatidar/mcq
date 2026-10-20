package com.mcqapp

import androidx.test.core.app.ApplicationProvider
import com.mcqapp.ui.importscreen.ImportViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import screen's numeric fields used to be rendered from the PARSED
 * value (`if (x == 0) "" else x.toString()`), so every keystroke's parse
 * rewrote the field: typing "0" blanked it immediately and "0.5" was
 * un-typeable. The raw text must be owned by the state, not re-derived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class ImportFieldTextTest {

    private fun viewModel() = ImportViewModel(
        ApplicationProvider.getApplicationContext() as McqApplication
    )

    @Test
    fun theFirstZeroKeystrokeStaysInsteadOfBlankingTheField() {
        val vm = viewModel()
        vm.updateNegativeMarkingText("0")
        val s = vm.state.value
        assertEquals("the field must keep the typed '0'", "0", s.negativeMarkingText)
        assertEquals(0.0, s.negativeMarking, 0.0)
    }

    @Test
    fun aCommaDecimalEntrySurvivesAndParses() {
        val vm = viewModel()
        vm.updateNegativeMarkingText("0,5")
        val s = vm.state.value
        assertEquals("the field keeps what was typed", "0,5", s.negativeMarkingText)
        assertEquals(0.5, s.negativeMarking, 0.0)
    }
}
