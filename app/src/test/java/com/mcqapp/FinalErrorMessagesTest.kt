package com.mcqapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.io.FALLBACK_CATEGORY_TITLE
import com.mcqapp.data.io.FALLBACK_PAPER_TITLE
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.history.BookmarksViewModel
import com.mcqapp.ui.importscreen.ImportViewModel
import com.mcqapp.ui.settings.SettingsViewModel
import com.mcqapp.ui.stats.StatsViewModel
import com.mcqapp.ui.theme.InvalidThemeException
import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.SendCopyResult
import com.mcqapp.util.SharedImport
import com.mcqapp.util.copySendToCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The learner-facing error copy is friendly and localized; the raw exception
 * detail stays in the log. Each test forces a failure and checks that the
 * exact resource string is shown and that a marker planted in the exception
 * never reaches the UI state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class FinalErrorMessagesTest {

    private lateinit var app: McqApplication

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        try {
            // A couple of tests close the process-wide singleton database.
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitIdle(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun aFailingSettingsExportShowsOnlyFriendlyCopy() {
        val viewModel = SettingsViewModel(app)
        val reported = mutableListOf<String>()
        runBlocking {
            viewModel.exportAllOn(
                Uri.parse("content://unused/export.json"),
                onError = { reported += it },
                open = { throw IOException("SECRET-DISK-DETAIL") }
            )
        }
        assertEquals(listOf(string(R.string.final_export_failed)), reported)
        assertFalse("raw cause leaked: $reported", reported.any { "SECRET-DISK-DETAIL" in it || "Exception" in it })
    }

    @Test
    fun aMissingImportSourceShowsFriendlyPickAgainCopy() {
        val viewModel = ImportViewModel(app)
        viewModel.setNoSourceError()
        val error = viewModel.state.value.error
        assertEquals(string(R.string.final_pick_again), error)
        assertFalse("raw cause leaked: $error", error!!.contains("Exception"))
    }

    @Test
    fun fallbackTokensResolveToLocalizedDisplayTitles() {
        val viewModel = ImportViewModel(app)
        assertEquals(
            string(R.string.final_fallback_category_title),
            viewModel.resolveFallbackTitle(FALLBACK_CATEGORY_TITLE)
        )
        assertEquals(
            string(R.string.final_fallback_paper_title),
            viewModel.resolveFallbackTitle(FALLBACK_PAPER_TITLE)
        )
        assertEquals("a real title", viewModel.resolveFallbackTitle("a real title"))
    }

    /**
     * A Hindi locale must resolve the persisted fallback-title tokens to the
     * Hindi display strings while the preview state — and the file handed to
     * the importer — keep the canonical English tokens (SQL/search compare by
     * equality). This exercises the same [ImportViewModel.resolveFallbackTitle]
     * seam the Compose render path uses through `stringResource`.
     */
    @Test
    @Config(sdk = [34], application = TestMcqApplication::class, qualifiers = "hi")
    fun hindiLocaleDisplaysLocalizedFallbackButStoresCanonicalToken() {
        val viewModel = ImportViewModel(app)
        // A bare question array is wrapped by the parser in its fallback paper
        // and category, so the preview state carries both canonical tokens.
        val bareQuestions = """
            [{"id":"q1",
              "question_elements":[{"type":"text","content":"Q?"}],
              "options_elements":{"a":[{"type":"text","content":"Yes"}],
                                  "b":[{"type":"text","content":"No"}]},
              "correctOptionIds":["a"]}]
        """.trimIndent()
        viewModel.loadJsonText(bareQuestions)
        assertTrue(awaitIdle {
            !viewModel.state.value.loading && viewModel.state.value.questions.isNotEmpty()
        })

        val state = viewModel.state.value
        // Storage and the file actually imported keep the canonical tokens.
        assertEquals(FALLBACK_CATEGORY_TITLE, state.categoryName)
        assertEquals(FALLBACK_PAPER_TITLE, state.paperTitle)
        val written = com.mcqapp.ui.importscreen.buildImportFile(state, state.originalFile)
        assertEquals(FALLBACK_CATEGORY_TITLE, written.papers[0].categories[0].title)
        assertEquals(FALLBACK_PAPER_TITLE, written.papers[0].title)

        // The learner reads the Hindi strings, never the English tokens.
        val hindiCategory = string(R.string.final_fallback_category_title)
        val hindiPaper = string(R.string.final_fallback_paper_title)
        assertTrue("Hindi resource must differ from the stored token", hindiCategory != FALLBACK_CATEGORY_TITLE)
        assertEquals(hindiCategory, viewModel.resolveFallbackTitle(state.categoryName))
        assertEquals(hindiPaper, viewModel.resolveFallbackTitle(state.paperTitle))
    }

    @Test
    fun aFailedBookmarkExportShowsOnlyFriendlyCopy() {
        val viewModel = BookmarksViewModel(app)
        app.repository.db().close()
        viewModel.exportBookmarks(
            Uri.parse("content://unused/bookmarks.json"),
            ExportFormat.JSON_INLINE
        )
        assertTrue("a failed export must surface an error", awaitIdle { viewModel.exportError.value != null })
        val error = viewModel.exportError.value!!
        assertEquals(string(R.string.final_bookmarks_export_failed), error)
        assertFalse("raw cause leaked: $error", "Exception" in error || "closed" in error)
    }

    @Test
    fun aFailedCardDetailLoadShowsOnlyFriendlyCopy() {
        val viewModel = StatsViewModel(app)
        app.repository.db().close()
        viewModel.openCard("paper-1", "question-1")
        assertTrue("a failed load must surface an error", awaitIdle { viewModel.cardDetail.value?.error != null })
        val error = viewModel.cardDetail.value!!.error!!
        assertEquals(string(R.string.final_card_detail_load_failed), error)
        assertFalse("raw cause leaked: $error", "Exception" in error || "closed" in error)
    }

    @Test
    fun aFailedRestoreShowsOnlyFriendlyCopy() {
        val viewModel = SettingsViewModel(app)
        val reported = mutableListOf<String>()
        runBlocking {
            viewModel.restoreBackupHandling(
                onError = { reported += it },
                onDone = {},
                onComplete = {},
                restore = { throw IllegalStateException("SECRET-BACKUP-DETAIL") }
            )
        }
        assertEquals(listOf(string(R.string.final_restore_failed)), reported)
        assertFalse("raw cause leaked: $reported", reported.any { "SECRET-BACKUP-DETAIL" in it || "Exception" in it })
    }

    @Test
    fun anOversizedRestoreShowsTheFriendlyLimitCopy() {
        val viewModel = SettingsViewModel(app)
        val reported = mutableListOf<String>()
        runBlocking {
            viewModel.restoreBackupHandling(
                onError = { reported += it },
                onDone = {},
                onComplete = {},
                restore = { throw ImportTooLargeException(limit = 256L * 1024 * 1024) }
            )
        }
        assertEquals(listOf(string(R.string.final_restore_too_large, 256L)), reported)
        assertFalse("raw cause leaked: $reported", reported.any { "Exception" in it })
    }

    @Test
    fun anUnreadableSharedFileCarriesAFriendlyResourceId() {
        val result = copySendToCache(app, Intent(Intent.ACTION_SEND))
        assertTrue(result is SendCopyResult.Failed)
        val failure = result as SendCopyResult.Failed
        assertEquals(R.string.final_pick_again, failure.resId)
        assertTrue(failure.args.isEmpty())
        assertEquals(string(R.string.final_pick_again), string(failure.resId))
    }

    @Test
    fun aSharedImportFailureIsSurfacedAsPlainResIdAndArgs() {
        SharedImport.requestError(R.string.final_import_too_large, listOf(64L, 70L))
        val failure = SharedImport.consumeError()!!
        assertEquals(R.string.final_import_too_large, failure.resId)
        assertEquals(listOf(64L, 70L), failure.args)
        assertEquals(
            string(R.string.final_import_too_large, 64L, 70L),
            string(failure.resId, *failure.args.toTypedArray())
        )
    }

    @Test
    fun aMalformedThemeThrowsAResourceIdNotEnglishCopy() {
        val viewModel = SettingsViewModel(app)
        val rejection = assertThrows(InvalidThemeException::class.java) {
            runBlocking { viewModel.importThemeText("not json") }
        }
        assertEquals(R.string.final_theme_invalid, rejection.resId)
        assertTrue(rejection.args.isEmpty())
    }
}
