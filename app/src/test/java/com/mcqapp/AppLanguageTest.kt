package com.mcqapp

import com.mcqapp.data.repository.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM mapping from the persisted language choice to the locale the app
 * applies. The platform call itself needs an Android runtime; the decision of
 * *which* locale (or none) to apply is plain data and belongs here.
 */
class AppLanguageTest {

    @Test
    fun systemYieldsNoLocaleOverride() {
        assertNull(AppLanguage.SYSTEM.localeTag())
        assertNull(AppLanguage.SYSTEM.locale())
    }

    @Test
    fun englishMapsToTheEnglishLocale() {
        assertEquals("en", AppLanguage.ENGLISH.localeTag())
        assertEquals("en", AppLanguage.ENGLISH.locale()?.toLanguageTag())
    }

    @Test
    fun hindiMapsToTheHindiLocale() {
        assertEquals("hi", AppLanguage.HINDI.localeTag())
        assertEquals("hi", AppLanguage.HINDI.locale()?.toLanguageTag())
    }

    @Test
    fun missingAndUnknownStoredValuesFallBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("HI"))
    }

    @Test
    fun storedValuesRoundTripAndAreStable() {
        assertEquals(
            setOf("system", "en", "hi"),
            AppLanguage.entries.map { it.storedValue }.toSet()
        )
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromStored(language.storedValue))
        }
    }

    @Test
    fun platformRecreationSuppressesTheExplicitRecreateSignal() {
        // API 33+ recreates the activity itself on a locale change; a second
        // signal from the ViewModel would restart it twice.
        assertFalse(needsExplicitLanguageRecreate(platformRecreates = true))
        // Below that, only attachBaseContext picks the new locale up, so the
        // screen has to ask for a recreation.
        assertTrue(needsExplicitLanguageRecreate(platformRecreates = false))
    }
}
