package com.mcqapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A palette the learner imported. It carries the same seed colours as the
 * built-in themes and goes through the same [buildScheme] derivation, so an
 * imported theme cannot produce unreadable text either.
 */
internal data class CustomTheme(
    val key: String,
    val name: String,
    val spec: ThemeSpec
) {
    fun scheme(dark: Boolean): ColorScheme = buildScheme(spec, dark)

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
}

/** The selected key resolved to a scheme; unknown or deleted keys fall back. */
internal fun resolveScheme(
    key: String,
    customThemes: List<CustomTheme>,
    dark: Boolean
): ColorScheme =
    customThemes.firstOrNull { it.key == key }?.scheme(dark)
        ?: AppTheme.fromKey(key).scheme(dark)

internal class InvalidThemeException(message: String) : Exception(message)

/** How many imported themes one install may keep. */
internal const val MAX_CUSTOM_THEMES = 20

/**
 * Ceiling for a theme file. Twelve six-digit colours and a name fit in a
 * couple of kilobytes; anything larger is a bank or a zip picked by mistake.
 */
internal const val MAX_THEME_FILE_BYTES = 64 * 1024

internal data class ParsedCustomTheme(val name: String, val spec: ThemeSpec)

/**
 * The import format. One object; `light` and `dark` each describe the four
 * surface colours, while the four accents are shared because the derivation
 * already deepens or lightens them per mode.
 *
 * ```json
 * {
 *   "name": "Midnight",
 *   "primary": "#7aa2f7",
 *   "secondary": "#bb9af7",
 *   "tertiary": "#7dcfff",
 *   "error": "#f7768e",
 *   "light": {
 *     "background": "#e6e7ed", "surface": "#ffffff",
 *     "surfaceVariant": "#c4c8da", "onBackground": "#3760bf"
 *   },
 *   "dark": {
 *     "background": "#1a1b26", "surface": "#16161e",
 *     "surfaceVariant": "#292e42", "onBackground": "#c0caf5"
 *   }
 * }
 * ```
 */
@Serializable
internal data class CustomThemeFile(
    val name: String,
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val error: String,
    val light: CustomThemeModeFile,
    val dark: CustomThemeModeFile
) {
    fun toSpec(): ThemeSpec = ThemeSpec(
        primary = parseColor(primary, "primary"),
        secondary = parseColor(secondary, "secondary"),
        tertiary = parseColor(tertiary, "tertiary"),
        error = parseColor(error, "error"),
        lightBackground = parseColor(light.background, "light.background"),
        lightSurface = parseColor(light.surface, "light.surface"),
        lightSurfaceVariant = parseColor(light.surfaceVariant, "light.surfaceVariant"),
        lightOnBackground = parseColor(light.onBackground, "light.onBackground"),
        darkBackground = parseColor(dark.background, "dark.background"),
        darkSurface = parseColor(dark.surface, "dark.surface"),
        darkSurfaceVariant = parseColor(dark.surfaceVariant, "dark.surfaceVariant"),
        darkOnBackground = parseColor(dark.onBackground, "dark.onBackground")
    )
}

@Serializable
internal data class CustomThemeModeFile(
    val background: String,
    val surface: String,
    val surfaceVariant: String,
    val onBackground: String
)

@Serializable
private data class StoredCustomTheme(val key: String, val file: CustomThemeFile)

/**
 * Reads and writes imported themes. Decoding is forgiving per entry: one bad
 * theme restored from an old backup is skipped instead of emptying the list.
 * Importing is strict: every colour must be a six-digit hex value and the
 * name must be present, because the error can still be shown to the user.
 */
internal object CustomThemeCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(source: String): ParsedCustomTheme {
        val file = try {
            json.decodeFromString<CustomThemeFile>(source)
        } catch (e: Exception) {
            throw InvalidThemeException(
                "That file is not a theme file (expected JSON with a name, four accents and light/dark colours)."
            )
        }
        val name = file.name.trim()
        if (name.isEmpty() || name.length > 40) {
            throw InvalidThemeException("A theme needs a name of 1–40 characters.")
        }
        if (name.any { it.isISOControl() }) {
            throw InvalidThemeException("The theme name contains control characters.")
        }
        return ParsedCustomTheme(name, file.toSpec())
    }

    fun decodeAll(raw: String?): List<CustomTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        // Parse the array first, then each entry on its own: one theme
        // restored from an old backup with a missing field must not hide its
        // good neighbours.
        val array = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                val entry = json.decodeFromJsonElement<StoredCustomTheme>(element)
                val name = entry.file.name.trim()
                if (name.isEmpty()) null
                else CustomTheme(entry.key, name, entry.file.toSpec())
            } catch (_: Exception) {
                null
            }
        }
    }

    fun encodeAll(themes: List<CustomTheme>): String =
        json.encodeToString(
            themes.map { theme ->
                StoredCustomTheme(theme.key, theme.spec.toFile(theme.name))
            }
        )

}

private val hexColor = Regex("^#?([0-9a-fA-F]{6})$")

private fun parseColor(value: String, field: String): Color {
    val match = hexColor.matchEntire(value.trim())
        ?: throw InvalidThemeException(
            "$field must be a hex colour like #7AA2F7 (six digits)."
        )
    return Color(0xFF000000L or match.groupValues[1].toLong(16))
}

private fun ThemeSpec.toFile(name: String) = CustomThemeFile(
    name = name,
    primary = primary.toHex(),
    secondary = secondary.toHex(),
    tertiary = tertiary.toHex(),
    error = error.toHex(),
    light = CustomThemeModeFile(
        background = lightBackground.toHex(),
        surface = lightSurface.toHex(),
        surfaceVariant = lightSurfaceVariant.toHex(),
        onBackground = lightOnBackground.toHex()
    ),
    dark = CustomThemeModeFile(
        background = darkBackground.toHex(),
        surface = darkSurface.toHex(),
        surfaceVariant = darkSurfaceVariant.toHex(),
        onBackground = darkOnBackground.toHex()
    )
)

/**
 * `Color` -> `#RRGGBB` without `android.graphics.toArgb`, so the codec also
 * runs in the pure-JVM unit tests.
 */
private fun Color.toHex(): String {
    fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
    return String.format(
        Locale.ROOT,
        "#%02X%02X%02X",
        channel(red),
        channel(green),
        channel(blue)
    )
}
