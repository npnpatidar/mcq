package com.mcqapp.data.io

import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.local.AppDatabase
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Imports an Anki `.apkg` by streaming it straight into the database.
 *
 * `.apkg` is the last import that used to be read whole: the reader expanded
 * the archive into a `Map<String, ByteArray>`, turned every picture into a data
 * URI inside one DTO for the whole bank, and `Importer` held that beside a
 * whole-bank content-hash set. A deck big enough to matter was refused at the
 * archive cap or died under it, on top of the file the picker had already read
 * to bytes.
 *
 * This reads the archive to temp files under the same caps, spools each picture
 * once, and walks the collection one deck at a time, handing each batch of
 * questions to the shared [StreamingImportSession] — the same writer the JSON
 * path uses, so the merge decisions (paper by id then title, question by content
 * hash, refresh answers on duplicate) and the review schedules are the whole
 * file importer's. `AnkiStreamEquivalenceTest` holds the two readers to the same
 * database over the fixtures.
 */
class AnkiApkgImporter(
    private val db: AppDatabase,
    private val updateAnswersOnDuplicate: Boolean = false,
    private val batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams the package [source] opens. [source] is called exactly once, on
     * the IO dispatcher, and must return a fresh stream over the file.
     *
     * [onProgress] runs after each batch with the running count of questions
     * read, so a long import can show it is alive.
     */
    suspend fun import(
        source: () -> InputStream,
        onProgress: ((Int) -> Unit)? = null
    ): ImportReport = withContext(Dispatchers.IO) {
        val session = StreamingImportSession(
            db = db,
            json = json,
            updateAnswersOnDuplicate = updateAnswersOnDuplicate,
            batchSize = batchSize,
            overrides = ImportOverrides(),
            onProgress = onProgress
        )
        val sink = object : AnkiPackageReader.StreamSink {
            override suspend fun onPaper(header: LegacyParser.PaperHeader) = session.onPaper(header)

            override suspend fun onCategory(category: CategoryDto) = session.onCategory(category)

            override suspend fun onQuestions(
                categoryId: String,
                questions: List<QuestionDto>,
                schedules: Map<String, CardScheduleDto>
            ) = session.onQuestions(categoryId, questions, schedules)
        }
        val tail = source().use { AnkiPackageReader.stream(it, sink, batchSize) }
        session.finish(
            LegacyParser.StreamedFile(
                version = 1,
                passages = tail.passages,
                bookmarks = emptyList(),
                attempts = emptyList(),
                scheduling = emptyMap(),
                warnings = emptyList()
            )
        )
        session.report()
    }
}
