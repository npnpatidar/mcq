package com.mcqapp.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.mcqapp.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colour themes the app ships. [MATERIAL] is the stock Material 3 scheme
 * the app has always used, so an existing install looks unchanged until the
 * learner picks something else.
 *
 * Every other entry carries a compact seed: the theme's background, its panel
 * surface and four accent colours. [buildScheme] derives the rest of the
 * Material roles from those, which keeps fifteen themes reviewable as colour
 * data instead of fifteen full schemes (thirty-four roles apiece). The
 * derivation also enforces WCAG AA contrast on the pairs that carry text, so
 * a dim palette such as Solarized cannot produce an unreadable body line.
 *
 * The keys are stored in DataStore, so they are part of the backup format:
 * rename one and old backups fall back to Material instead of matching.
 */
internal enum class AppTheme(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val summaryRes: Int
) {
    MATERIAL("material", R.string.theme_material, R.string.theme_material_summary),
    NORD("nord", R.string.theme_nord, R.string.theme_nord_summary),
    DRACULA("dracula", R.string.theme_dracula, R.string.theme_dracula_summary),
    TOKYO_NIGHT("tokyo_night", R.string.theme_tokyo_night, R.string.theme_tokyo_night_summary),
    CATPPUCCIN("catppuccin", R.string.theme_catppuccin, R.string.theme_catppuccin_summary),
    GRUVBOX("gruvbox", R.string.theme_gruvbox, R.string.theme_gruvbox_summary),
    ONE_DARK("one_dark", R.string.theme_one_dark, R.string.theme_one_dark_summary),
    MONOKAI("monokai", R.string.theme_monokai, R.string.theme_monokai_summary),
    SOLARIZED("solarized", R.string.theme_solarized, R.string.theme_solarized_summary),
    EVERFOREST("everforest", R.string.theme_everforest, R.string.theme_everforest_summary),
    ROSE_PINE("rose_pine", R.string.theme_rose_pine, R.string.theme_rose_pine_summary),
    AYU("ayu", R.string.theme_ayu, R.string.theme_ayu_summary),
    NIGHT_OWL("night_owl", R.string.theme_night_owl, R.string.theme_night_owl_summary),
    KANAGAWA("kanagawa", R.string.theme_kanagawa, R.string.theme_kanagawa_summary),
    GITHUB("github", R.string.theme_github, R.string.theme_github_summary);

    /** The five colours the picker shows as a swatch strip. */
    fun previewColors(dark: Boolean): List<Color> {
        val scheme = scheme(dark)
        return listOf(
            scheme.background,
            scheme.surfaceVariant,
            scheme.primary,
            scheme.secondary,
            scheme.tertiary
        )
    }

    fun scheme(dark: Boolean): ColorScheme = when (this) {
        // The stock schemes, untouched: "Material" is the previous look, and
        // tests pin it to lightColorScheme()/darkColorScheme() exactly.
        MATERIAL -> if (dark) darkColorScheme() else lightColorScheme()
        else -> buildScheme(spec, dark)
    }

    private val spec: ThemeSpec
        get() = when (this) {
            MATERIAL -> error("Material uses the stock schemes")
            NORD -> ThemeSpec(
                primary = Color(0xFF88C0D0),
                secondary = Color(0xFF81A1C1),
                tertiary = Color(0xFFB48EAD),
                error = Color(0xFFBF616A),
                darkBackground = Color(0xFF2E3440),
                darkSurface = Color(0xFF2E3440),
                darkSurfaceVariant = Color(0xFF434C5E),
                darkOnBackground = Color(0xFFECEFF4),
                lightBackground = Color(0xFFECEFF4),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFD8DEE9),
                lightOnBackground = Color(0xFF2E3440)
            )
            DRACULA -> ThemeSpec(
                primary = Color(0xFFBD93F9),
                secondary = Color(0xFFFF79C6),
                tertiary = Color(0xFF8BE9FD),
                error = Color(0xFFFF5555),
                darkBackground = Color(0xFF282A36),
                darkSurface = Color(0xFF21222C),
                darkSurfaceVariant = Color(0xFF44475A),
                darkOnBackground = Color(0xFFF8F8F2),
                lightBackground = Color(0xFFFFFBEB),
                lightSurface = Color(0xFFFFFDF5),
                lightSurfaceVariant = Color(0xFFE8E3D3),
                lightOnBackground = Color(0xFF282A36)
            )
            TOKYO_NIGHT -> ThemeSpec(
                primary = Color(0xFF7AA2F7),
                secondary = Color(0xFFBB9AF7),
                tertiary = Color(0xFF7DCFFF),
                error = Color(0xFFF7768E),
                darkBackground = Color(0xFF1A1B26),
                darkSurface = Color(0xFF16161E),
                darkSurfaceVariant = Color(0xFF292E42),
                darkOnBackground = Color(0xFFC0CAF5),
                lightBackground = Color(0xFFE6E7ED),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFC4C8DA),
                lightOnBackground = Color(0xFF3760BF)
            )
            CATPPUCCIN -> ThemeSpec(
                primary = Color(0xFF89B4FA),
                secondary = Color(0xFFCBA6F7),
                tertiary = Color(0xFF94E2D5),
                error = Color(0xFFF38BA8),
                darkBackground = Color(0xFF1E1E2E),
                darkSurface = Color(0xFF181825),
                darkSurfaceVariant = Color(0xFF313244),
                darkOnBackground = Color(0xFFCDD6F4),
                lightBackground = Color(0xFFEFF1F5),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFCCD0DA),
                lightOnBackground = Color(0xFF4C4F69)
            )
            GRUVBOX -> ThemeSpec(
                primary = Color(0xFF83A598),
                secondary = Color(0xFFD3869B),
                tertiary = Color(0xFFFABD2F),
                error = Color(0xFFFB4934),
                darkBackground = Color(0xFF282828),
                darkSurface = Color(0xFF1D2021),
                darkSurfaceVariant = Color(0xFF3C3836),
                darkOnBackground = Color(0xFFEBDBB2),
                lightBackground = Color(0xFFFBF1C7),
                lightSurface = Color(0xFFF9F5D7),
                lightSurfaceVariant = Color(0xFFEBDBB2),
                lightOnBackground = Color(0xFF3C3836)
            )
            ONE_DARK -> ThemeSpec(
                primary = Color(0xFF61AFEF),
                secondary = Color(0xFFC678DD),
                tertiary = Color(0xFF56B6C2),
                error = Color(0xFFE06C75),
                darkBackground = Color(0xFF282C34),
                darkSurface = Color(0xFF21252B),
                darkSurfaceVariant = Color(0xFF3E4451),
                darkOnBackground = Color(0xFFABB2BF),
                lightBackground = Color(0xFFFAFAFA),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFE5E5E6),
                lightOnBackground = Color(0xFF383A42)
            )
            MONOKAI -> ThemeSpec(
                primary = Color(0xFF66D9EF),
                secondary = Color(0xFFA6E22E),
                tertiary = Color(0xFFAE81FF),
                error = Color(0xFFF92672),
                darkBackground = Color(0xFF272822),
                darkSurface = Color(0xFF1E1F1C),
                darkSurfaceVariant = Color(0xFF3E3D32),
                darkOnBackground = Color(0xFFF8F8F2),
                lightBackground = Color(0xFFFAFAF2),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFE8E8DC),
                lightOnBackground = Color(0xFF272822)
            )
            SOLARIZED -> ThemeSpec(
                primary = Color(0xFF268BD2),
                secondary = Color(0xFF2AA198),
                tertiary = Color(0xFF6C71C4),
                error = Color(0xFFDC322F),
                darkBackground = Color(0xFF002B36),
                darkSurface = Color(0xFF073642),
                darkSurfaceVariant = Color(0xFF0E4450),
                darkOnBackground = Color(0xFF93A1A1),
                lightBackground = Color(0xFFFDF6E3),
                lightSurface = Color(0xFFEEE8D5),
                lightSurfaceVariant = Color(0xFFDDD6C1),
                lightOnBackground = Color(0xFF657B83)
            )
            EVERFOREST -> ThemeSpec(
                primary = Color(0xFFA7C080),
                secondary = Color(0xFF7FBBB3),
                tertiary = Color(0xFFD699B6),
                error = Color(0xFFE67E80),
                darkBackground = Color(0xFF2D353B),
                darkSurface = Color(0xFF343F44),
                darkSurfaceVariant = Color(0xFF3D484D),
                darkOnBackground = Color(0xFFD3C6AA),
                lightBackground = Color(0xFFFDF6E3),
                lightSurface = Color(0xFFF4F0D9),
                lightSurfaceVariant = Color(0xFFEAE4CC),
                lightOnBackground = Color(0xFF5C6A72)
            )
            ROSE_PINE -> ThemeSpec(
                primary = Color(0xFFC4A7E7),
                secondary = Color(0xFF9CCFD8),
                tertiary = Color(0xFFEBBCBA),
                error = Color(0xFFEB6F92),
                darkBackground = Color(0xFF191724),
                darkSurface = Color(0xFF1F1D2E),
                darkSurfaceVariant = Color(0xFF26233A),
                darkOnBackground = Color(0xFFE0DEF4),
                lightBackground = Color(0xFFFAF4ED),
                lightSurface = Color(0xFFFFF9F2),
                lightSurfaceVariant = Color(0xFFF2E9E1),
                lightOnBackground = Color(0xFF575279)
            )
            AYU -> ThemeSpec(
                primary = Color(0xFFFFCC66),
                secondary = Color(0xFF73D0FF),
                tertiary = Color(0xFFD4BFFF),
                error = Color(0xFFF28779),
                darkBackground = Color(0xFF1F2430),
                darkSurface = Color(0xFF232834),
                darkSurfaceVariant = Color(0xFF2D3645),
                darkOnBackground = Color(0xFFCBCCC6),
                lightBackground = Color(0xFFFAFAFA),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFE7EAED),
                lightOnBackground = Color(0xFF5C6773)
            )
            NIGHT_OWL -> ThemeSpec(
                primary = Color(0xFF82AAFF),
                secondary = Color(0xFFC792EA),
                tertiary = Color(0xFF7FDBCA),
                error = Color(0xFFEF5350),
                darkBackground = Color(0xFF011627),
                darkSurface = Color(0xFF0B2942),
                darkSurfaceVariant = Color(0xFF1D3B53),
                darkOnBackground = Color(0xFFD6DEEB),
                lightBackground = Color(0xFFFBFBFB),
                lightSurface = Color(0xFFFFFFFF),
                lightSurfaceVariant = Color(0xFFEDEDED),
                lightOnBackground = Color(0xFF403F53)
            )
            KANAGAWA -> ThemeSpec(
                primary = Color(0xFF7FB4CA),
                secondary = Color(0xFF957FB8),
                tertiary = Color(0xFF98BB6C),
                error = Color(0xFFE46876),
                darkBackground = Color(0xFF1F1F28),
                darkSurface = Color(0xFF2A2A37),
                darkSurfaceVariant = Color(0xFF363646),
                darkOnBackground = Color(0xFFDCD7BA),
                lightBackground = Color(0xFFF2ECBC),
                lightSurface = Color(0xFFE5DDAD),
                lightSurfaceVariant = Color(0xFFDDD5A9),
                lightOnBackground = Color(0xFF545464)
            )
            GITHUB -> ThemeSpec(
                primary = Color(0xFF58A6FF),
                secondary = Color(0xFFBC8CFF),
                tertiary = Color(0xFF3FB950),
                error = Color(0xFFF85149),
                darkBackground = Color(0xFF0D1117),
                darkSurface = Color(0xFF161B22),
                darkSurfaceVariant = Color(0xFF21262D),
                darkOnBackground = Color(0xFFC9D1D9),
                lightBackground = Color(0xFFFFFFFF),
                lightSurface = Color(0xFFF6F8FA),
                lightSurfaceVariant = Color(0xFFEAEEF2),
                lightOnBackground = Color(0xFF24292F)
            )
        }

    companion object {
        /** Unknown keys (a restored future backup, say) fall back to Material. */
        fun fromKey(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: MATERIAL
    }
}

/** The seed colours a theme is built from; see [AppTheme]. */
internal data class ThemeSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceVariant: Color,
    val darkOnBackground: Color,
    val lightBackground: Color,
    val lightSurface: Color,
    val lightSurfaceVariant: Color,
    val lightOnBackground: Color
)

/**
 * Derives every Material 3 role from a [ThemeSpec].
 *
 * Containers are a tint of their accent over the theme's own background, so
 * they inherit the palette instead of the stock purple. Foreground roles run
 * through [readableOn], which walks the colour toward black or white until it
 * clears the requested contrast ratio: designed accents keep their hue where
 * they already work and are deepened/lightened where they do not.
 */
internal fun buildScheme(spec: ThemeSpec, dark: Boolean): ColorScheme {
    val background = if (dark) spec.darkBackground else spec.lightBackground
    val surface = if (dark) spec.darkSurface else spec.lightSurface
    val surfaceVariant = if (dark) spec.darkSurfaceVariant else spec.lightSurfaceVariant
    val seedForeground = if (dark) spec.darkOnBackground else spec.lightOnBackground

    val onBackground = readableOn(seedForeground, background)
    val onSurface = readableOn(seedForeground, surface)
    val primary = readableOn(spec.primary, surface)
    val secondary = readableOn(spec.secondary, surface)
    val tertiary = readableOn(spec.tertiary, surface)
    val error = readableOn(spec.error, surface)

    val primaryContainer = tonalContainer(primary, background, dark)
    val secondaryContainer = tonalContainer(secondary, background, dark)
    val tertiaryContainer = tonalContainer(tertiary, background, dark)
    val errorContainer = tonalContainer(error, background, dark)

    val onSurfaceVariant = readableOn(mix(onSurface, surface, 0.30f), surfaceVariant)

    // M3's elevation ladder: light schemes step darker from the surface, dark
    // schemes step lighter, and each theme's own surface carries the tint.
    val containerLowest: Color
    val containerLow: Color
    val container: Color
    val containerHigh: Color
    val containerHighest: Color
    if (dark) {
        containerLowest = mix(surface, Color.Black, 0.40f)
        containerLow = mix(surface, onSurface, 0.035f)
        container = mix(surface, onSurface, 0.055f)
        containerHigh = mix(surface, onSurface, 0.08f)
        containerHighest = mix(surface, onSurface, 0.11f)
    } else {
        containerLowest = mix(surface, Color.White, 0.65f)
        containerLow = mix(surface, onSurface, 0.03f)
        container = mix(surface, onSurface, 0.05f)
        containerHigh = mix(surface, onSurface, 0.075f)
        containerHighest = mix(surface, onSurface, 0.10f)
    }

    val surfaceBright =
        if (dark) mix(surface, onSurface, 0.14f) else mix(surface, Color.White, 0.50f)
    val surfaceDim =
        if (dark) mix(surface, Color.Black, 0.28f) else mix(surface, onSurface, 0.09f)
    val inverseSurface =
        if (dark) mix(surface, onSurface, 0.90f) else mix(surface, onSurface, 0.85f)
    val inverseOnSurface = contentColorFor(inverseSurface)

    val outline = readableOn(
        mix(onSurface, surface, if (dark) 0.42f else 0.52f),
        surface,
        minimum = 3.0
    )
    val outlineVariant = mix(onSurface, surface, if (dark) 0.72f else 0.82f)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = readableOn(primary, primaryContainer),
            inversePrimary = readableOn(primary, inverseSurface),
            secondary = secondary,
            onSecondary = contentColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = readableOn(secondary, secondaryContainer),
            tertiary = tertiary,
            onTertiary = contentColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = readableOn(tertiary, tertiaryContainer),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = contentColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = readableOn(error, errorContainer),
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            surfaceContainerLow = containerLow,
            surfaceContainerLowest = containerLowest
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = readableOn(primary, primaryContainer),
            inversePrimary = readableOn(primary, inverseSurface),
            secondary = secondary,
            onSecondary = contentColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = readableOn(secondary, secondaryContainer),
            tertiary = tertiary,
            onTertiary = contentColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = readableOn(tertiary, tertiaryContainer),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = contentColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = readableOn(error, errorContainer),
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            surfaceContainerLow = containerLow,
            surfaceContainerLowest = containerLowest
        )
    }
}

/** A tint of [accent] over the theme's background, as M3 containers are. */
private fun tonalContainer(accent: Color, background: Color, dark: Boolean): Color =
    mix(accent, background, if (dark) 0.76f else 0.86f)

/**
 * The legible text colour to put on a filled [color] surface. Pure black and
 * white are the two extremes that together cover every luminance, so the
 * winner always clears 4.5:1; a softer near-black can leave a mid-tone fill
 * (Nord's light cyan, say) below the bar.
 */
private fun contentColorFor(color: Color): Color =
    if (contrastRatio(Color.White, color) >= contrastRatio(Color.Black, color)) {
        Color.White
    } else {
        Color.Black
    }

/**
 * Returns [foreground] if it already clears [minimum] against [background],
 * otherwise the least adjustment toward white or black (whichever can reach a
 * higher contrast) that does. Keeps a palette's hue wherever the design allows
 * it and refuses to render text that cannot be read.
 */
private fun readableOn(
    foreground: Color,
    background: Color,
    minimum: Double = 4.5
): Color {
    if (contrastRatio(foreground, background) >= minimum) return foreground
    val towardWhite = contrastRatio(Color.White, background) >=
        contrastRatio(Color.Black, background)
    val target = if (towardWhite) Color.White else Color.Black
    var low = 0f
    var high = 1f
    // Contrast moves monotonically as the colour walks to the target, so a
    // bisection finds the smallest shift that clears the bar.
    repeat(20) {
        val mid = (low + high) / 2f
        if (contrastRatio(mix(foreground, target, mid), background) >= minimum) {
            high = mid
        } else {
            low = mid
        }
    }
    val adjusted = mix(foreground, target, high)
    return if (contrastRatio(adjusted, background) >= minimum) adjusted else target
}

/** WCAG relative luminance. */
private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** WCAG contrast ratio, 1.0 (same colour) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun mix(a: Color, b: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f
    )
}
