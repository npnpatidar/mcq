package com.mcqapp

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.mcqapp.ui.Verdict
import com.mcqapp.ui.theme.containerColor
import com.mcqapp.ui.theme.contrastRatio
import com.mcqapp.ui.theme.onContainerColor
import com.mcqapp.ui.theme.verdictColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM: the verdict enum and its role-derived palette carry no Android
 * runtime dependency, so every mapping can be pinned on the host. These fail on
 * the old code, where the four states were separate `when` branches in the
 * screen and the CSV writer and the light palette was a set of fixed hexes.
 */
class VerdictTest {

    @Test
    fun allFourStatesDeriveFromKeySelectionAndCorrectness() {
        assertEquals(Verdict.CORRECT, Verdict.of(setOf("a"), setOf("a"), true))
        assertEquals(Verdict.WRONG, Verdict.of(setOf("a"), setOf("b"), false))
        assertEquals(Verdict.SKIPPED, Verdict.of(setOf("a"), emptySet(), false))
        // No answer key wins over everything: skipped and selected are ungraded.
        assertEquals(Verdict.UNGRADED, Verdict.of(emptySet(), emptySet(), false))
        assertEquals(Verdict.UNGRADED, Verdict.of(emptySet(), setOf("b"), false))
    }

    @Test
    fun everyVerdictHasADistinctLabelGlyphCsvValueAndContainerPair() {
        assertEquals(4, Verdict.entries.size)
        assertEquals(
            4,
            Verdict.entries.map { it.labelRes }.toSet().size
        )
        assertEquals(
            listOf("✓", "✗", "–", "?"),
            Verdict.entries.map { it.glyph }
        )
        assertEquals(
            listOf("correct", "wrong", "skipped", "ungraded"),
            Verdict.entries.map { it.csvValue }
        )
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(
                "containers must be distinct so the state is not colour-collapsed",
                4,
                Verdict.entries.map { it.containerColor(scheme) }.toSet().size
            )
        }
    }

    @Test
    fun skippedIsNeutralNotWrongRedAndOnContainerRolesMatch() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(scheme.surfaceVariant, Verdict.SKIPPED.containerColor(scheme))
            assertEquals(scheme.onSurfaceVariant, Verdict.SKIPPED.onContainerColor(scheme))
            assertEquals(scheme.errorContainer, Verdict.WRONG.containerColor(scheme))
            assertEquals(scheme.onErrorContainer, Verdict.WRONG.onContainerColor(scheme))
            assertNotEquals(
                "skipped must not reuse the wrong container",
                Verdict.WRONG.containerColor(scheme),
                Verdict.SKIPPED.containerColor(scheme)
            )
            for (verdict in Verdict.entries) {
                assertTrue(
                    "${verdict.name}: on-container text must clear AA on its container",
                    contrastRatio(
                        verdict.onContainerColor(scheme),
                        verdict.containerColor(scheme)
                    ) >= 4.5
                )
            }
        }
    }

    @Test
    fun verdictColoursFollowTheActiveSchemeInBothModes() {
        val custom = lightColorScheme().copy(
            primaryContainer = Color(0xFF112233),
            onPrimaryContainer = Color(0xFFFFEEFF),
            errorContainer = Color(0xFF443322),
            onErrorContainer = Color(0xFFFFDDCC),
            surfaceVariant = Color(0xFF445566),
            onSurfaceVariant = Color(0xFFEEDDFF),
            tertiaryContainer = Color(0xFF776655),
            onTertiaryContainer = Color(0xFFFFFFEE)
        )
        assertEquals(custom.primaryContainer, Verdict.CORRECT.containerColor(custom))
        assertEquals(custom.onPrimaryContainer, Verdict.CORRECT.onContainerColor(custom))
        assertEquals(custom.errorContainer, Verdict.WRONG.containerColor(custom))
        assertEquals(custom.surfaceVariant, Verdict.SKIPPED.containerColor(custom))
        assertEquals(custom.tertiaryContainer, Verdict.UNGRADED.containerColor(custom))

        val palette = verdictColorsFor(custom)
        assertEquals(custom.primaryContainer, palette.correctContainer)
        assertEquals(custom.errorContainer, palette.wrongContainer)
        assertEquals(custom.primary, palette.rising)
        // The old light branch returned fixed hexes for every scheme.
        assertNotEquals(Color(0xFFC8E6C9), palette.correctContainer)
    }

    @Test
    fun csvIdStringsUseTheSameFourWayPredicate() {
        assertEquals(Verdict.CORRECT, Verdict.of("a", "a", true))
        assertEquals(Verdict.WRONG, Verdict.of("a", "b", false))
        assertEquals(Verdict.SKIPPED, Verdict.of("a", "", false))
        assertEquals(Verdict.UNGRADED, Verdict.of("", "b", false))
        assertEquals(Verdict.UNGRADED, Verdict.of("", "", false))
        // Multi-option ids stay correct regardless of selection order.
        assertEquals(Verdict.CORRECT, Verdict.of("a,b", "b,a", true))
    }
}
