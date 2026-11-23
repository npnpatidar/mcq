package com.mcqapp

import com.mcqapp.ui.library.shouldShowFirstRunChooser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chooser must never overwrite a real library or reappear on upgrades. */
class FirstRunChooserGateTest {

    @Test
    fun showsOnlyOnFreshInstallWithNoPapersAndNoMarker() {
        assertTrue(shouldShowFirstRunChooser(true, false, false))
    }

    @Test
    fun anUpgradeNeverSeesTheChooser() {
        assertFalse(shouldShowFirstRunChooser(false, false, false))
    }

    @Test
    fun existingPapersNeverSeeTheChooser() {
        assertFalse(shouldShowFirstRunChooser(true, true, false))
    }

    @Test
    fun anAnsweredChooserNeverReappears() {
        assertFalse(shouldShowFirstRunChooser(true, false, true))
    }
}
