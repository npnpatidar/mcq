package com.mcqapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.mcqapp.domain.ReviewGrade

/**
 * Matched foreground/background pairs for the four filled Study grade buttons.
 *
 * The buttons used to set `containerColor` to a semantic role (error, tertiary,
 * primary, secondary) while leaving Material's default `onPrimary` text. That
 * is unreadable under a custom palette, where `onPrimary` is chosen for the
 * primary container, not for the role used as another button's fill. The pair
 * resolver keeps the existing fills and resolves the matching `on*` role from
 * the *same* scheme, exactly as [verdictPaletteFor] does for verdict cards.
 * Pure so it can be checked in light, dark and custom themes without Compose.
 */
internal data class GradePalette(val container: Color, val onContainer: Color)

/** Pure grade-to-role-pair resolver, shared by the grade buttons and their tests. */
internal fun gradePaletteFor(grade: ReviewGrade, scheme: ColorScheme): GradePalette =
    when (grade) {
        ReviewGrade.AGAIN -> GradePalette(scheme.error, scheme.onError)
        ReviewGrade.HARD -> GradePalette(scheme.tertiary, scheme.onTertiary)
        ReviewGrade.GOOD -> GradePalette(scheme.primary, scheme.onPrimary)
        ReviewGrade.EASY -> GradePalette(scheme.secondary, scheme.onSecondary)
    }

@Composable
@ReadOnlyComposable
internal fun gradePalette(grade: ReviewGrade): GradePalette =
    gradePaletteFor(grade, MaterialTheme.colorScheme)
