package com.mcqapp

import com.mcqapp.ui.settings.ThemePaletteOnRemoval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SE4: removing the selected custom theme must clear the palette key even when
 * the async-cached StateFlow snapshot still disagrees. The shipped helper takes
 * the authoritative stored value, so the "stale cache" case is simply the
 * positive case below: the stored palette names the theme being removed.
 */
class Round2SettingsThemeTest {

    private val material = "material"

    @Test
    fun removingTheSelectedThemeClearsThePaletteKey() {
        // Stored palette was the just-imported key the cache had not published.
        assertEquals(
            material,
            ThemePaletteOnRemoval.resolve(
                storedPalette = "custom-42",
                removedKey = "custom-42",
                materialKey = material
            )
        )
    }

    @Test
    fun removingADifferentThemeKeepsTheSelectedCustomPalette() {
        assertEquals(
            "custom-7",
            ThemePaletteOnRemoval.resolve(
                storedPalette = "custom-7",
                removedKey = "custom-42",
                materialKey = material
            )
        )
    }

    @Test
    fun removingAThemeWhileABuiltInIsSelectedKeepsTheBuiltIn() {
        assertEquals(
            "dark",
            ThemePaletteOnRemoval.resolve(
                storedPalette = "dark",
                removedKey = "custom-42",
                materialKey = material
            )
        )
    }
}
