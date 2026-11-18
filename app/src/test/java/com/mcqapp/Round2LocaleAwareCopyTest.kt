package com.mcqapp

import com.mcqapp.data.repository.AppLanguage
import com.mcqapp.util.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM seam for the shared locale-aware copy resolver added in round 2.
 *
 * A ViewModel holds the Application context, which is never wrapped with the
 * in-app language. The helper must therefore feed the selected language through
 * [AppStrings.languageForCopy] on API < 33, while letting the platform own the
 * locale on 33+. The string lookup itself needs Android; the cross-API language
 * decision is plain data and belongs here.
 */
class Round2LocaleAwareCopyTest {

    @Test
    fun below33ResolvesCopyWithTheSelectedLanguage() {
        // Positive: the explicit override reaches the copy resolver when the
        // Activity is the only wrapped context (API < 33).
        assertEquals(AppLanguage.HINDI, AppStrings.languageForCopy(AppLanguage.HINDI, needsWrap = true))
        assertEquals(AppLanguage.ENGLISH, AppStrings.languageForCopy(AppLanguage.ENGLISH, needsWrap = true))
        assertEquals(
            "hi",
            AppStrings.languageForCopy(AppLanguage.HINDI, needsWrap = true)
                .locale()?.toLanguageTag()
        )
    }

    @Test
    fun systemStaysDeviceDefaultOnEveryApiLevel() {
        // Negative: SYSTEM never becomes an override, so its copy follows the
        // device exactly as before — on both resolution paths.
        listOf(true, false).forEach { needsWrap ->
            assertEquals(AppLanguage.SYSTEM, AppStrings.languageForCopy(AppLanguage.SYSTEM, needsWrap))
            assertNull(AppStrings.languageForCopy(AppLanguage.SYSTEM, needsWrap).locale())
        }
    }

    @Test
    fun platformLocalesSuppressTheExplicitOverride() {
        // On 33+ the platform applies the app's locales to every context, so the
        // helper hands SYSTEM through and AppLocale.localized returns it as-is;
        // wrapping again would double-apply the locale.
        assertEquals(AppLanguage.SYSTEM, AppStrings.languageForCopy(AppLanguage.HINDI, needsWrap = false))
        assertEquals(AppLanguage.SYSTEM, AppStrings.languageForCopy(AppLanguage.ENGLISH, needsWrap = false))
    }

    @Test
    fun copyLanguageMatchesTheLocaleTheActivityWrapsWith() {
        // Parity: below 33 the copy resolver uses the same selected choice the
        // Activity wrap uses, so a warning string cannot disagree with Compose.
        AppLanguage.entries.forEach { selected ->
            assertEquals(
                selected.locale()?.toLanguageTag(),
                AppStrings.languageForCopy(selected, needsWrap = true)
                    .locale()?.toLanguageTag()
            )
        }
    }
}
