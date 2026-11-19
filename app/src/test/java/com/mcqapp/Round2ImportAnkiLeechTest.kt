package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiReadResult
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.anki.AnkiScheduling
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.QuestionDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * D1 at the *production seam*: the whole-file [AnkiPackageReader.read] and the
 * streamed [AnkiPackageReader.stream] readers must both carry the configured
 * leech threshold into `AnkiScheduling.fromAnki`. Anki stores only `lapses`; the
 * derived `leech` flag has to agree with the scheduler the learner will study
 * with. A 5-lapse card is a leech at threshold 3 but not at the default 8.
 *
 * CI-only locally: Robolectric's SQLite native runtime does not run on ARM64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round2ImportAnkiLeechTest {

    private val crt = 1_700_000_000L
    private val sep = AnkiPackageWriter.FIELD_SEPARATOR

    /** One reviewed card with 5 lapses — a leech only below the default 8. */
    private fun packageWithFiveLapses(): ByteArray {
        val file = File.createTempFile("r2-leech", ".anki2")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "create table col (id integer primary key, crt integer not null, mod integer not null, " +
                "scm integer not null, ver integer not null, dty integer not null, usn integer not null, " +
                "ls integer not null, conf text not null, models text not null, decks text not null, " +
                "dconf text not null, tags text not null)"
        )
        db.execSQL("create table notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)")
        db.execSQL("create table cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)")
        db.execSQL("create table revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)")
        val models = """{"1":{"id":1,"name":"Basic","type":0,"flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{Back}}"}]}}"""
        db.execSQL(
            "insert into col values (1, ?, 0, 0, 11, 0, 0, 0, '{}', ?, '{\"1\":{\"id\":1,\"name\":\"Default\"}}', '{}', '{}')",
            arrayOf<Any>(crt, models)
        )
        val flds = "Front: 2 + 2?${sep}&#10003; 4<br>&#10007; five"
        db.execSQL(
            "insert into notes values (1, ?, 1, 0, 0, ' mcqapp ', ?, '', 0, 0, '')",
            arrayOf<Any>(AnkiPackageWriter.guidFor("t", "n1"), flds)
        )
        db.execSQL(
            "insert into cards values (1001, 1, 1, 0, 0, 0, 2, 2, 5, 5, 2600, 7, 5, 0, 0, 0, 0, '')"
        )
        db.execSQL(
            "insert into revlog values (${(crt - 3600) * 1000}, 1001, -1, 3, 5, 1, 2600, 32500, 0)"
        )
        val path = db.path
        db.close()
        val collection = File(path).readBytes()
        File(path).delete()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            zip.write(collection)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("media"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun questionIdOf(result: AnkiReadResult): String =
        result.file.papers.single().let { paper ->
            (paper.questions + paper.categories.flatMap { it.questions }).single().id
        }

    @Test
    fun wholeFileReaderUsesTheConfiguredThreshold() {
        val bytes = packageWithFiveLapses()
        val configured = AnkiPackageReader.read(bytes, leechThreshold = 3)
        val state = configured.scheduling[questionIdOf(configured)]
        assertNotNull(state)
        assertTrue("5 lapses >= configured 3", state!!.leech)
        assertEquals(5, state.lapses)

        val default = AnkiPackageReader.read(bytes)
        val defaultState = default.scheduling[questionIdOf(default)]
        assertNotNull(defaultState)
        assertFalse("5 lapses < default 8", defaultState!!.leech)
        assertEquals(AnkiScheduling.LEECH_THRESHOLD, 8)
    }

    @Test
    fun streamingReaderUsesTheConfiguredThreshold() = runBlocking {
        val bytes = packageWithFiveLapses()

        suspend fun streamed(threshold: Int?): CardScheduleDto? {
            var found: CardScheduleDto? = null
            AnkiPackageReader.stream(
                input = java.io.ByteArrayInputStream(bytes),
                sink = object : AnkiPackageReader.StreamSink {
                    override suspend fun onPaper(header: LegacyParser.PaperHeader) = Unit
                    override suspend fun onCategory(category: CategoryDto) = Unit
                    override suspend fun onQuestions(
                        categoryId: String,
                        questions: List<QuestionDto>,
                        schedules: Map<String, CardScheduleDto>
                    ) {
                        if (found == null && schedules.isNotEmpty()) found = schedules.values.first()
                    }
                },
                leechThreshold = threshold ?: AnkiScheduling.LEECH_THRESHOLD
            )
            return found
        }

        val configured = streamed(3)
        assertNotNull(configured)
        assertTrue("5 lapses >= configured 3", configured!!.leech)

        val default = streamed(null)
        assertNotNull(default)
        assertFalse("5 lapses < default 8", default!!.leech)
    }
}
