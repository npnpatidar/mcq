package com.mcqapp

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.theme.contrastRatio
import com.mcqapp.ui.theme.gradePaletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four filled grade buttons used to set only `containerColor` and keep
 * Material's `onPrimary` text. With a custom palette that pairs a pale accent
 * with a dark on-colour, an error/tertiary fill was unreadable. The resolver
 * returns a matched role pair from the *same* scheme, like verdictPaletteFor.
 * Pure JVM — no Compose host needed.
 */
class GradePaletteTest {

    @Test
    fun eachGradeUsesItsOwnMatchedContainerAndOnContainerRoles() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(scheme.error to scheme.onError, pair(scheme, ReviewGrade.AGAIN))
            assertEquals(scheme.tertiary to scheme.onTertiary, pair(scheme, ReviewGrade.HARD))
            assertEquals(scheme.primary to scheme.onPrimary, pair(scheme, ReviewGrade.GOOD))
            assertEquals(scheme.secondary to scheme.onSecondary, pair(scheme, ReviewGrade.EASY))
            // Four distinct fills: colour is a cue, never the only one, but a
            // collapsed palette would still erase the ordering the labels imply.
            assertEquals(
                4,
                ReviewGrade.entries.map { gradePaletteFor(it, scheme).container }.toSet().size
            )
        }
    }

    @Test
    fun everyGradePairClearsAaInLightAndDark() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            for (grade in ReviewGrade.entries) {
                val palette = gradePaletteFor(grade, scheme)
                assertTrue(
                    "${grade.name}: ${palette.onContainer} on ${palette.container} is only " +
                        "%.2f:1".format(contrastRatio(palette.onContainer, palette.container)),
                    contrastRatio(palette.onContainer, palette.container) >= 4.5
                )
            }
        }
    }

    @Test
    fun aCustomPaletteNeverLeavesOnPrimaryTextOnAnotherRolesFill() {
        // A deliberately clashing scheme: onPrimary is near-white while the
        // error fill is a light yellow. The old buttonColors(containerColor =
        // error) kept the near-white onPrimary and failed this contrast bar.
        val custom = lightColorScheme().copy(
            primary = Color(0xFF3D5A80),
            onPrimary = Color(0xFFFDFDFD),
            error = Color(0xFFF2E680),
            onError = Color(0xFF322D00),
            tertiary = Color(0xFFD8C2F0),
            onTertiary = Color(0xFF29103F),
            secondary = Color(0xFFA8DADC),
            onSecondary = Color(0xFF0B3033)
        )
        val again = gradePaletteFor(ReviewGrade.AGAIN, custom)
        assertEquals(custom.error, again.container)
        assertEquals(custom.onError, again.onContainer)
        assertNotEquals("the fill must not fall back to primary", custom.primary, again.container)
        assertNotEquals(
            "onPrimary must never be used on the error fill",
            custom.onPrimary,
            again.onContainer
        )
        for (grade in ReviewGrade.entries) {
            val palette = gradePaletteFor(grade, custom)
            assertTrue(
                "${grade.name}: custom pair is only %.2f:1".format(
                    contrastRatio(palette.onContainer, palette.container)
                ),
                contrastRatio(palette.onContainer, palette.container) >= 4.5
            )
        }
    }

    private fun pair(
        scheme: androidx.compose.material3.ColorScheme,
        grade: ReviewGrade
    ) = gradePaletteFor(grade, scheme).let { it.container to it.onContainer }
}
