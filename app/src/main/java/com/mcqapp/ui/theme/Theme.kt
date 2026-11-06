package com.mcqapp.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Applies the learner's mode (system/light/dark) and colour theme.
 *
 * [themePalette] is a key from [AppTheme] or an imported [CustomTheme]. A key
 * that no longer resolves — a deleted imported theme restored from a backup,
 * say — renders Material rather than leaving the screen unstyled.
 */
@Composable
internal fun McqTheme(
    themeMode: String,
    themePalette: String = AppTheme.MATERIAL.key,
    customThemes: List<CustomTheme> = emptyList(),
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkMode(themeMode)
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        // enableEdgeToEdge() only knows the system's light/dark setting. A
        // forced dark theme on a light-system phone would otherwise paint
        // dark status-bar icons on the app's dark background.
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = resolveScheme(themePalette, customThemes, darkTheme),
        content = content
    )
}

/** The effective light/dark choice; shared with the theme picker's previews. */
@Composable
internal fun isDarkMode(themeMode: String): Boolean = when (themeMode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
