package com.mcqapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.mcqapp.ui.Verdict

/**
 * Semantic colours for right/wrong/tricky signalling, plus the trend arrows.
 *
 * Every value comes from the active Material 3 colour scheme, in both light and
 * dark mode. The old light branch returned fixed hexes, which ignored custom
 * light themes and could pair a pale card with unreadable text. Callers still
 * pair the colour with a glyph or a word — colour alone is never the signal.
 */
data class VerdictColors(
    val correctContainer: Color,
    val correctOnContainer: Color,
    val correctBorder: Color,
    val wrongContainer: Color,
    val wrongOnContainer: Color,
    val wrongBorder: Color,
    val tricky: Color,
    val trickyContainer: Color,
    val trickyOnContainer: Color,
    val rising: Color,
    val falling: Color
)

/** Role-derived palette for one scheme; pure so it can be tested without Compose. */
internal fun verdictColorsFor(scheme: ColorScheme): VerdictColors = VerdictColors(
    correctContainer = scheme.primaryContainer,
    correctOnContainer = scheme.onPrimaryContainer,
    correctBorder = scheme.primary,
    wrongContainer = scheme.errorContainer,
    wrongOnContainer = scheme.onErrorContainer,
    wrongBorder = scheme.error,
    tricky = scheme.tertiary,
    trickyContainer = scheme.tertiaryContainer,
    trickyOnContainer = scheme.onTertiaryContainer,
    rising = scheme.primary,
    falling = scheme.error
)

@Composable
@ReadOnlyComposable
fun verdictColors(): VerdictColors = verdictColorsFor(MaterialTheme.colorScheme)

/**
 * Container and matching `on*Container` pair for one verdict, resolved from
 * the active scheme. Skipped stays neutral: a skip is not a wrong answer.
 */
internal data class VerdictPalette(val container: Color, val onContainer: Color)

/** Pure verdict colour resolver, shared by the results card and its tests. */
internal fun verdictPaletteFor(verdict: Verdict, scheme: ColorScheme): VerdictPalette =
    when (verdict) {
        Verdict.CORRECT -> VerdictPalette(
            scheme.primaryContainer,
            scheme.onPrimaryContainer
        )
        Verdict.WRONG -> VerdictPalette(
            scheme.errorContainer,
            scheme.onErrorContainer
        )
        Verdict.SKIPPED -> VerdictPalette(
            scheme.surfaceVariant,
            scheme.onSurfaceVariant
        )
        Verdict.UNGRADED -> VerdictPalette(
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer
        )
    }

/** The verdict's M3 container role, used as a result card's background. */
internal fun Verdict.containerColor(scheme: ColorScheme): Color =
    verdictPaletteFor(this, scheme).container

/** The matching `on*Container` role — never `primary`/`error` on a container. */
internal fun Verdict.onContainerColor(scheme: ColorScheme): Color =
    verdictPaletteFor(this, scheme).onContainer

/**
 * The three tints the Study question clock uses for its dwell bands.
 *
 * The clock is the one caller that paints *text* in these semantics, so its
 * light tints stay the traffic-light green/amber rather than an arbitrary
 * theme accent; they are then walked to legibility against whichever surface
 * the active theme uses. Dark mode uses the scheme's own accents. The single
 * [Color.isDark] helper (shared with AppThemes) decides which branch applies.
 */
data class ClockBandColors(val easy: Color, val good: Color, val hard: Color)

private object LightClockTints {
    val rising = Color(0xFF2E7D32)
    val tricky = Color(0xFFE65100)
}

/** [ClockBandColors] for one scheme, with every tint legible on its surface. */
internal fun clockBandColorsFor(scheme: ColorScheme): ClockBandColors {
    val dark = scheme.background.isDark()
    return ClockBandColors(
        easy = readableOn(
            if (dark) scheme.primary else LightClockTints.rising,
            scheme.surface
        ),
        good = readableOn(
            if (dark) scheme.tertiary else LightClockTints.tricky,
            scheme.surface
        ),
        hard = readableOn(scheme.error, scheme.surface)
    )
}

@Composable
@ReadOnlyComposable
fun clockBandColors(): ClockBandColors = clockBandColorsFor(MaterialTheme.colorScheme)
