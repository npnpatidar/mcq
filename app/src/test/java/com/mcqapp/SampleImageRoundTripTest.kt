package com.mcqapp

import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.parseContentElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sample paper's pictures must survive the whole pipeline: asset JSON →
 * parse → import → stored rows. A renderer or importer change that drops
 * images showed up on the device as "no images except questions 14 and 53"
 * with no test to catch it — this pins the data side; the UI side renders
 * whatever these rows hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleImageRoundTripTest {

    @Test
    fun importingTheSampleAssetKeepsItsPictures() = kotlinx.coroutines.runBlocking {
        val text = java.io.File("src/main/assets/sample_paper.json").readText()
        val file = LegacyParser.parse(text.byteInputStream())
        val parsedWithImages = file.papers.flatMap { it.categories }.flatMap { it.questions }
            .count { q ->
                q.elements.any { it is ContentElement.ImageElement } ||
                    q.options.any { o -> o.elements.any { it is ContentElement.ImageElement } }
            }
        assertTrue("the asset itself carries pictures (parsed $parsedWithImages)", parsedWithImages >= 6)

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.mcqapp.data.local.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val report = Importer(db).import(file)

        val categoryIds = file.papers.flatMap { it.categories }.map { it.id }
        val stored = categoryIds.flatMap { db.questionDao().getByCategory(it) }
        assertEquals("every question stored", file.papers.flatMap { it.categories }.sumOf { it.questions.size }, stored.size)

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val storedWithImages = stored.count { q ->
            q.text.parseContentElements(json).any { it is ContentElement.ImageElement } ||
                db.optionDao().getForQuestions(listOf(q.id)).any { o -> o.image != null }
        }
        assertTrue(
            "stored rows must keep the pictures (parsed $parsedWithImages, stored $storedWithImages)",
            storedWithImages >= parsedWithImages
        )
        // And the stored element JSON must still be a decodable ImageElement
        // carrying its bytes.
        val decoded = stored.flatMap { q -> q.text.parseContentElements(json) }
        assertTrue(decoded.any { it is ContentElement.ImageElement && it.src.startsWith("data:") })
        db.close()
    }
}
