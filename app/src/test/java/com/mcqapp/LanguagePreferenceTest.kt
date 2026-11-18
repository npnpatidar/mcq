package com.mcqapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.AppLanguage
import com.mcqapp.data.repository.SettingsStore
import com.mcqapp.data.repository.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore persistence for the in-app language. Robolectric without a
 * database: the preference is a plain string, so these run wherever
 * RepositorySettingsTest runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguagePreferenceTest {

    private val key = stringPreferencesKey(AppLanguage.PREFERENCE_KEY)

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>()

    private fun store(): SettingsStore = SettingsStore(context(), freshInstall = false)

    @Before
    fun clearPreference() {
        runBlocking { context().dataStore.edit { it.remove(key) } }
    }

    @After
    fun clearAgain() {
        runBlocking { context().dataStore.edit { it.remove(key) } }
    }

    @Test
    fun defaultIsSystemWithNoStoredOverride() = runBlocking {
        assertEquals(AppLanguage.SYSTEM, store().appLanguage().first())
        assertNull(context().dataStore.data.first()[key])
        assertNull("SYSTEM must not apply a locale", AppLanguage.SYSTEM.locale())
    }

    @Test
    fun everyChoicePersistsAndRestores() = runBlocking {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.HINDI, AppLanguage.SYSTEM)) {
            store().setAppLanguage(language)
            assertEquals(language, store().appLanguage().first())
            val stored = context().dataStore.data.first()[key]
            if (language == AppLanguage.SYSTEM) {
                assertNull("choosing SYSTEM clears the override", stored)
            } else {
                assertEquals(language.storedValue, stored)
            }
        }
    }

    @Test
    fun aCorruptStoredValueIsReadAsSystemAndCanBeOverridden() = runBlocking {
        context().dataStore.edit { it[key] = "fr" }
        assertEquals(AppLanguage.SYSTEM, store().appLanguage().first())

        store().setAppLanguage(AppLanguage.HINDI)
        assertEquals(AppLanguage.HINDI, store().appLanguage().first())
        assertEquals("hi", context().dataStore.data.first()[key])
    }
}
