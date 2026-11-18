package com.mcqapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.R
import com.mcqapp.data.io.FALLBACK_CATEGORY_LABEL
import com.mcqapp.data.io.FALLBACK_CLOZE_EXPLANATION
import com.mcqapp.data.io.FALLBACK_PASSAGE_TITLE
import com.mcqapp.ui.importscreen.fallbackTitleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I6 — the extra fallback literals (blank DOCX heading/passage, Anki cloze
 * explanation) are stable stored tokens whose *display* is localized. The
 * token values never change; only the learner-facing copy does.
 *
 * This test is resource-only (no Room), so it also runs on x86-64 CI and needs
 * no process-wide database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round2ImportLocalizationTest {

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    @Test
    fun tokenValuesAreTheCanonicalUnchangedLiterals() {
        assertEquals("Category", FALLBACK_CATEGORY_LABEL)
        assertEquals("Passage", FALLBACK_PASSAGE_TITLE)
        assertEquals("Cloze card from Anki.", FALLBACK_CLOZE_EXPLANATION)
    }

    @Test
    fun tokensMapToTheirNewDisplayResources() {
        assertEquals(R.string.r2_import_fallback_category, fallbackTitleRes(FALLBACK_CATEGORY_LABEL))
        assertEquals(R.string.r2_import_fallback_passage, fallbackTitleRes(FALLBACK_PASSAGE_TITLE))
        assertEquals(
            R.string.r2_import_fallback_cloze_explanation,
            fallbackTitleRes(FALLBACK_CLOZE_EXPLANATION)
        )
        // A real title still has no mapping.
        assertEquals(null, fallbackTitleRes("A real category"))
    }

    @Test
    fun baseLocaleDisplaysTheCanonicalEnglishCopy() {
        assertEquals(FALLBACK_CATEGORY_LABEL, string(R.string.r2_import_fallback_category))
        assertEquals(FALLBACK_PASSAGE_TITLE, string(R.string.r2_import_fallback_passage))
        assertEquals(FALLBACK_CLOZE_EXPLANATION, string(R.string.r2_import_fallback_cloze_explanation))
    }

    @Test
    @Config(sdk = [34], qualifiers = "hi")
    fun hindiLocaleDisplaysLocalizedCopyForTheSameStoredTokens() {
        val category = string(R.string.r2_import_fallback_category)
        val passage = string(R.string.r2_import_fallback_passage)
        val cloze = string(R.string.r2_import_fallback_cloze_explanation)

        assertNotNull(category)
        assertNotNull(passage)
        assertNotNull(cloze)
        assertTrue("category must be translated for Hindi", category != FALLBACK_CATEGORY_LABEL)
        assertTrue("passage must be translated for Hindi", passage != FALLBACK_PASSAGE_TITLE)
        assertTrue("cloze explanation must be translated for Hindi", cloze != FALLBACK_CLOZE_EXPLANATION)

        // The storage token is untouched: the rendered copy differs, the
        // persisted value the importer writes does not.
        assertEquals("Category", FALLBACK_CATEGORY_LABEL)
        assertEquals("Passage", FALLBACK_PASSAGE_TITLE)
        assertEquals("Cloze card from Anki.", FALLBACK_CLOZE_EXPLANATION)
    }
}
