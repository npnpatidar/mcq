package com.mcqapp.util

import androidx.compose.runtime.compositionLocalOf

/**
 * App-wide text scaling, applied by overriding Density.fontScale at the
 * navigation root — every sp-sized text (questions, options, buttons)
 * scales together, like the system font-size setting but in-app.
 */
object FontScale {

    val OPTIONS = listOf(
        0.85f to "Small",
        1.0f to "Default",
        1.15f to "Large",
        1.3f to "Extra large"
    )

    const val DEFAULT = 1.0f

    val LocalScale = compositionLocalOf { DEFAULT }

    /** Snaps arbitrary stored values to the nearest offered option. */
    fun coerce(raw: Float): Float =
        OPTIONS.minByOrNull { kotlin.math.abs(it.first - raw) }?.first ?: DEFAULT
}
