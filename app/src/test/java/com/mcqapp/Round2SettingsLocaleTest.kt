package com.mcqapp

import com.mcqapp.data.repository.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SE6: the pre-33 language mirror. `attachBaseContext` reads the cached choice
 * instead of running a blocking DataStore collect on the main thread, so what
 * the picker/restore writes is exactly what attach sees. Pure JVM — only the
 * mapping is under test; the Android SharedPreferences round-trip is exercised
 * by the platform on device.
 */
class Round2SettingsLocaleTest {

    @Test
    fun explicitChoicesMirrorToTheRightStoredValues() {
        assertEquals("en", AppLanguageCache.storedValue(AppLanguage.ENGLISH))
        assertEquals("hi", AppLanguageCache.storedValue(AppLanguage.HINDI))
    }

    @Test
    fun systemClearsTheCachedOverride() {
        // SYSTEM must remove the key, not leave a stale override behind.
        assertNull(
            "SYSTEM must clear the mirrored override",
            AppLanguageCache.storedValue(AppLanguage.SYSTEM)
        )
    }

    @Test
    fun everyMirroredChoiceRoundTripsThroughFromStored() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                language,
                AppLanguage.fromStored(AppLanguageCache.storedValue(language))
            )
        }
    }

    @Test
    fun missingOrUnknownCachedValuesFallBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(""))
    }

    @Test
    fun platformRecreationSuppressesTheExplicitRecreateSignal() {
        // 33+ recreates for the locale change itself; a second signal would restart twice.
        assertFalse(needsExplicitLanguageRecreate(platformRecreates = true))
        // Below 33 only attach picks the language up, so the screen must ask for recreation.
        assertTrue(needsExplicitLanguageRecreate(platformRecreates = false))
    }
}
