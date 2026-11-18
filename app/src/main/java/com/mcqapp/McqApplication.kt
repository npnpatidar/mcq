package com.mcqapp

import androidx.annotation.RequiresApi
import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.mcqapp.data.anki.AnkiTempFiles
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.AppLanguage
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API 33+ recreates activities itself when the app's locales change, so the
 * screen must not ask for a second recreation. Below that the override is only
 * read at attach time, so the screen has to trigger it. [platformRecreates] is
 * a seam for a pure-JVM test.
 */
internal fun needsExplicitLanguageRecreate(
    platformRecreates: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
): Boolean = !platformRecreates

/**
 * The pre-33 language choice, mirrored out of DataStore so `attachBaseContext`
 * never has to run a blocking DataStore collect on the main thread (SE6).
 * DataStore stays the source of truth; this is only a cheap, synchronous mirror
 * for the one place that cannot suspend. It is written whenever the stored
 * choice changes (the picker and a settings restore) and read at attach.
 *
 * A `volatile` field is the hot path — attach after attach costs no I/O. The
 * SharedPreferences file is the cold-start fallback (a process kill discards
 * the field), and is deliberately tiny: one string. The `edit { }` extension
 * applies on a background thread; the in-memory value is already correct for
 * this process.
 */
internal object AppLanguageCache {
    private const val PREFS_NAME = "app_language_cache"
    private const val KEY = "language"

    @Volatile
    private var cached: AppLanguage? = null

    /** Pure JVM: how a choice is mirrored — SYSTEM clears the key (no override). */
    internal fun storedValue(language: AppLanguage): String? =
        if (language == AppLanguage.SYSTEM) null else language.storedValue

    internal fun read(context: Context): AppLanguage {
        cached?.let { return it }
        val stored = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, null)
        }.getOrNull()
        val language = AppLanguage.fromStored(stored)
        cached = language
        return language
    }

    internal fun write(context: Context, language: AppLanguage) {
        cached = language
        runCatching {
            val value = storedValue(language)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                if (value == null) remove(KEY) else putString(KEY, value)
            }
        }
    }
}

open class McqApplication : Application() {

    /**
     * Startup jobs must never take the process down. Each one already catches
     * its own failure, but this handler is the backstop for a bug in that
     * guard: a fatal exception there must not become an unrecoverable launch
     * loop.
     */
    private val startupExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e("APP", "Startup job failed", throwable)
    }

    /**
     * Process-scoped work that must finish eventually but must never block
     * startup or decide a caller's outcome. The FSRS-7 migration and card
     * rebuild live here (AGENTS.md: no WorkManager dependency), as does the
     * rebuild a backup restore starts — that one must never turn a committed
     * restore into "Restore failed". All of it is idempotent and resumable, so
     * a killed process simply resumes next launch.
     */
    private val startupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + startupExceptionHandler)

    /**
     * Whether `onCreate` launches the first-start grading-rule decision.
     * Production does: the answer must be taken before the learner can import
     * a paper in this run. Tests that pin their own preference fixtures opt
     * out, because the probe opens the real database and writes a
     * process-wide marker that would race their DataStore setup.
     */
    @VisibleForTesting
    internal open val seedsInstallFreshnessAtStartup: Boolean = true

    /**
     * Whether `onCreate` launches the FSRS-7 migration and memory rebuild.
     *
     * Both jobs open Room, so a test that owns its database fixtures opts out
     * for the same reason as the install-freshness probe.
     */
    @VisibleForTesting
    internal open val runsFsrs7StartupJobs: Boolean = true

    /**
     * Whether `onCreate` syncs the platform's per-app locales from the stored
     * language. Tests pin their own DataStore fixtures and must not inherit a
     * locale written by another class, so they opt out.
     */
    @VisibleForTesting
    internal open val syncsAppLocaleAtStartup: Boolean = true

    /** The production wiring: a new install is one whose database is empty. */
    val repository: McqRepository by lazy {
        McqRepository(AppDatabase.get(this), this, startupScope)
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        AnkiTempFiles.cleanup(this)
        Logger.i("APP", "Application onCreate (pid=${Process.myPid()})")

        // Install the handler before anything is launched: a fast failure in
        // the startup job must still be logged, and a crash while handling it
        // must not recurse into an unset handler.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.crash(throwable)
            Logger.e("APP", "Uncaught exception on thread '${thread.name}', pid=${Process.myPid()}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Log.e("APP", "No previous uncaught exception handler", throwable)
        }

        if (runsFsrs7StartupJobs) {
            startupScope.launch {
                try {
                    repository.ensureFsrs7()
                } catch (e: Exception) {
                    Logger.e("FSRS7", "model migration failed", e)
                }
                try {
                    repository.rebuildFsrs7Memory()
                } catch (e: Exception) {
                    Logger.e("FSRS7", "card-state rebuild failed; will resume next launch", e)
                }
            }
        }

        // Decide the first-start grading rule now, on IO. The answer comes
        // from database content, so it must be taken before the learner can
        // import a paper in this run — but it must not block onCreate or open
        // the database on the main thread. Every later config read awaits the
        // same decision, and a failure here must not crash the launch: the
        // probe already answers "not new" and the job catches anything else.
        if (seedsInstallFreshnessAtStartup) {
            startupScope.launch {
                runCatching { repository.decideInstallFreshness() }
                    .onFailure { Logger.e("APP", "Could not decide install freshness", it) }
            }
        }

        // The platform persists per-app locales itself, so it normally already
        // matches the stored choice on a cold start. Re-syncing here also covers
        // a settings restore that changed the language in a previous session.
        if (syncsAppLocaleAtStartup) {
            startupScope.launch {
                runCatching {
                    val language = repository.appLanguage().first()
                    // setApplicationLocales reconfigures the app and must be
                    // called on the main thread (SE6). The equality guard in
                    // applyPlatform makes this a no-op on an ordinary cold
                    // start; only a mismatch (e.g. a restored language) writes.
                    withContext(Dispatchers.Main) {
                        AppLocale.applyPlatform(this@McqApplication, language)
                    }
                }.onFailure { Logger.e("APP", "Could not sync app language", it) }
            }
        }
    }
}

/**
 * Applies the in-app language without touching the device's language.
 *
 * API 33+ uses the platform's per-app locales: the system persists them across
 * process death and applies them to every activity before it is created. Below
 * 33 there is no platform hook (and no AppCompat dependency here), so
 * [wrap] rewrites the activity's configuration from the stored preference at
 * attach time — the smallest path that actually changes Compose resources.
 */
internal object AppLocale {

    internal fun wrap(context: Context?): Context? {
        // On 33+ the platform has already applied the persisted locales to the
        // activity's base context, so the cache is never touched there. Below
        // 33 the mirrored choice is read synchronously — no DataStore collect,
        // no blocking disk read on the main thread (SE6).
        if (context == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        return localized(context, AppLanguageCache.read(context))
    }

    /**
     * A context whose resources use [language], for string resolution outside
     * an activity (e.g. the Settings ViewModel). On 33+ the platform already
     * applies the app's locales to every context, so nothing is wrapped. The
     * caller supplies the already-loaded language — no blocking DataStore read.
     */
    internal fun localized(context: Context, language: AppLanguage): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val locale = language.locale() ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** Applies the platform override on API 33+; a no-op on older devices. */
    internal fun applyPlatform(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        applyPlatform33(context, language)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyPlatform33(context: Context, language: AppLanguage) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val desired = language.locale()?.let { LocaleList.forLanguageTags(it.toLanguageTag()) }
            ?: LocaleList.getEmptyLocaleList()
        // Setting locales reconfigures the whole app, so skip the write when the
        // platform already holds this choice (or a redundant SYSTEM clear).
        if (manager.applicationLocales == desired) return
        manager.setApplicationLocales(desired)
    }

    /** Walks out of Compose's context wrappers to the activity and recreates it. */
    internal fun recreate(context: Context) {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) {
                current.recreate()
                return
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }
}
