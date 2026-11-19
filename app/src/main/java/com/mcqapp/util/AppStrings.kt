package com.mcqapp.util

import android.content.Context
import com.mcqapp.AppLanguageCache
import com.mcqapp.AppLocale
import com.mcqapp.data.repository.AppLanguage
import com.mcqapp.needsExplicitLanguageRecreate

/**
 * One locale-aware resolver for user-facing copy emitted outside Compose.
 *
 * Compose resources follow the Activity context, which [AppLocale.wrap]
 * reconfigures from the in-app language on API < 33 (on 33+ the platform applies
 * the app's locales to every context). A ViewModel holds the *Application*
 * context, which is never wrapped, so a plain `getString` there resolves with
 * the device locale even when the learner picked Hindi. Every such caller goes
 * through this helper instead.
 *
 * The selected language comes from [AppLanguageCache] — the same mirror
 * [AppLocale.wrap] reads — so there is a single source of truth, and
 * [AppLocale.localized] still owns the actual context wrapping.
 */
internal object AppStrings {

    /**
     * The language a non-Activity caller resolves copy with. Pure JVM seam.
     *
     * On API < 33 only the Activity is wrapped, so the Application context needs
     * the selected language explicitly. On 33+ the platform applies the app's
     * locales to every context and [AppLocale.localized] returns it unchanged;
     * SYSTEM therefore keeps the device/platform behaviour identical.
     */
    internal fun languageForCopy(
        selected: AppLanguage,
        needsWrap: Boolean
    ): AppLanguage = if (needsWrap) selected else AppLanguage.SYSTEM

    /** Resolve copy in the in-app selected language, with optional format args. */
    internal fun getString(context: Context, resId: Int, vararg args: Any): String {
        val language = selectedLanguage(context)
        return AppLocale.localized(context, language).getString(resId, *args)
    }

    /** Quantity-aware copy that agrees with a count ("1 card" / "N cards"). */
    internal fun getQuantityString(
        context: Context,
        resId: Int,
        quantity: Int,
        vararg args: Any
    ): String {
        val language = selectedLanguage(context)
        return AppLocale.localized(context, language)
            .resources.getQuantityString(resId, quantity, *args)
    }

    /**
     * Resolve with an explicit language, for the one caller (SettingsViewModel)
     * that already holds the live chosen language and must keep its exact
     * behaviour. [AppLocale.localized] still owns the cross-API decision.
     */
    internal fun getString(
        context: Context,
        language: AppLanguage,
        resId: Int,
        vararg args: Any
    ): String = AppLocale.localized(context, language).getString(resId, *args)

    internal fun getQuantityString(
        context: Context,
        language: AppLanguage,
        resId: Int,
        quantity: Int,
        vararg args: Any
    ): String = AppLocale.localized(context, language)
        .resources.getQuantityString(resId, quantity, *args)

    private fun selectedLanguage(context: Context): AppLanguage =
        languageForCopy(
            AppLanguageCache.read(context),
            needsExplicitLanguageRecreate()
        )
}
