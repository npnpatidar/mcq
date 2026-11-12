package com.mcqapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for right/wrong/tricky signalling.
 *
 * These used to be hardcoded light-theme hex values in five screens, which in
 * dark mode paired a pale green card with dark body text and left the glyph
 * colours without contrast. They now come from the active colour scheme, so
 * light and dark are both legible.
 *
 * Every caller still pairs the colour with a glyph or a word — colour alone is
 * never the only signal.
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
) {
    companion object {
        val Light = VerdictColors(
            correctContainer = Color(0xFFC8E6C9),
            correctOnContainer = Color(0xFF1B5E20),
            correctBorder = Color(0xFF2E7D32),
            wrongContainer = Color(0xFFFFCDD2),
            wrongOnContainer = Color(0xFFB71C1C),
            wrongBorder = Color(0xFFC62828),
            tricky = Color(0xFFE65100),
            trickyContainer = Color(0xFFFFCC80),
            trickyOnContainer = Color(0xFF7A3E00),
            rising = Color(0xFF2E7D32),
            falling = Color(0xFFC62828)
        )
    }
}

@Composable
@ReadOnlyComposable
fun verdictColors(): VerdictColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminanceIsDark()
    return if (dark) {
        VerdictColors(
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
    } else {
        VerdictColors.Light
    }
}

/** Cheap relative-luminance test; avoids a dependency for one boolean. */
private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

/**
 * The three tints the Study question clock uses for its dwell bands.
 *
 * The clock is the one caller that paints *text* in these semantics, and the
 * palette literals above are tuned for containers rather than for the surface
 * they sit on: the light amber is 3.70:1 on the light surface, under the
 * 4.5:1 the rest of the scheme guarantees. The tints keep the traffic-light
 * meaning (Easy green, Good amber, Hard error) and are then walked to
 * legibility against whichever surface the active theme uses.
 */
data class ClockBandColors(val easy: Color, val good: Color, val hard: Color)

/** [ClockBandColors] for one scheme, with every tint legible on its surface. */
internal fun clockBandColorsFor(scheme: ColorScheme): ClockBandColors {
    val dark = scheme.background.luminanceIsDark()
    return ClockBandColors(
        easy = readableOn(
            if (dark) scheme.primary else VerdictColors.Light.rising,
            scheme.surface
        ),
        good = readableOn(
            if (dark) scheme.tertiary else VerdictColors.Light.tricky,
            scheme.surface
        ),
        hard = readableOn(scheme.error, scheme.surface)
    )
}

@Composable
@ReadOnlyComposable
fun clockBandColors(): ClockBandColors = clockBandColorsFor(MaterialTheme.colorScheme)
