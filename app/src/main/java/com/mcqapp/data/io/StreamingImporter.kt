package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Edits made in the bounded preview, applied while the file streams so the
 * preview never had to hold it. Only the first paper is affected, matching the
 * single-paper preview the streaming path serves. A null field keeps the
 * file's value; [categoryTitle] renames the paper's first category (the
 * preview only offers a category name when the paper has exactly one).
 */
data class ImportOverrides(
    val paperTitle: String? = null,
    val paperDescription: String? = null,
    val durationMinutes: Int? = null,
    val negativeMarking: Double? = null,
    val categoryTitle: String? = null,
    val deletedIds: Set<String> = emptySet(),
    val editedQuestions: Map<String, QuestionDto> = emptyMap()
) {
    val isEmpty: Boolean
        get() = paperTitle == null && paperDescription == null && durationMinutes == null &&
            negativeMarking == null && categoryTitle == null && deletedIds.isEmpty() &&
            editedQuestions.isEmpty()
}

/**
 * Imports a JSON bank by streaming it straight into the database, one batch of
 * questions at a time, so a single paper with a thousand categories and a
 * million questions is never materialised. It is the write-side twin of
 * [LegacyParser.streamImport] and it reproduces [Importer]'s per-question
 * decisions exactly; the only observable differences are the ones the
 * whole-file importer's own quirks create (see [StreamingImportSession]), and
 * they are documented there.
 *
 * Write atomicity is per batch, not per file: a crashed import leaves the
 * batches it already committed. That is the price of never holding the bank,
 * and it is stated in the import screen's report.
 */
class StreamingImporter(
    private val db: AppDatabase,
    private val updateAnswersOnDuplicate: Boolean = false,
    private val batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE,
    private val overrides: ImportOverrides = ImportOverrides(),
    /**
     * Restore the file's tail `scheduling` for the questions this import
     * accepts. Off by default: it remembers each accepted question, and a plain
     * bank (no review progress) should not pay that. A file the preview
     * reported as carrying scheduling sets it.
     */
    private val captureSchedules: Boolean = false
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Mutant: the cancel flag outlives the run, so a retry is silenced. */
    private val cancelled = AtomicBoolean(false)

    /**
     * Streams the file [source] opens and writes it. [source] is called exactly
     * once, on the parsing thread, and may be called again by a retry; it must
     * return a fresh stream over the same bytes.
     *
     * [onProgress] runs on the consuming thread after each batch with the
     * running count of questions read, so a long import can show it is alive.
     */
    suspend fun import(
        source: () -> InputStream,
        onProgress: ((Int) -> Unit)? = null
    ): ImportReport = withContext(Dispatchers.IO) {
        importHere(source, onProgress)
    }

    /**
     * [import] on the caller's dispatcher.
     *
     * A caller that already holds a database transaction must use this one:
     * [import]'s hop to [Dispatchers.IO] would take the batch writes off the
     * thread that owns the transaction, so they would land outside it instead
     * of joining it. The parse still happens on its own thread — that thread
     * only reads the file.
     */
    suspend fun importInTransaction(
        source: () -> InputStream,
        onProgress: ((Int) -> Unit)? = null
    ): ImportReport = importHere(source, onProgress)

    private suspend fun importHere(
        source: () -> InputStream,
        onProgress: ((Int) -> Unit)?
    ): ImportReport {
        val session = StreamingImportSession(
            db, json, updateAnswersOnDuplicate, batchSize, overrides, onProgress, captureSchedules
        )
        val queue = ArrayBlockingQueue<StreamEvent>(16)
        val tailRef = AtomicReference<LegacyParser.StreamedFile>()
        val errorRef = AtomicReference<Throwable>()
        // The parser opens the stream on its own thread; the reference lets
        // the consumer's finally close it out from under a blocked read.
        val inputRef = AtomicReference<InputStream?>()


        fun offer(queue: ArrayBlockingQueue<StreamEvent>, event: StreamEvent) {
            if (cancelled.get()) return
            try {
                queue.put(event)
            } catch (_: InterruptedException) {
                // Once cancelled the parser is being torn down; the interrupt
                // flag staying set only turns later puts into instant throws.
                if (cancelled.get()) return
                Thread.currentThread().interrupt()
            }
        }

        val parser = Thread({
            try {
                val tail = source().use { input ->
                    inputRef.set(input)
                    LegacyParser.streamImport(input, object : LegacyParser.ImportStreamSink {
                        override fun onPaper(header: LegacyParser.PaperHeader) =
                            offer(queue, StreamEvent.Paper(header))
                        override fun onCategory(category: CategoryDto) =
                            offer(queue, StreamEvent.Category(category))
                        override fun onQuestions(categoryId: String, questions: List<QuestionDto>) =
                            offer(queue, StreamEvent.Questions(categoryId, questions))
                        // The tail streams through the same bounded queue, so
                        // its pages are written while the file is still being
                        // read and nothing of it accumulates here.
                        override fun onBookmarks(ids: List<String>) =
                            offer(queue, StreamEvent.Bookmarks(ids))
                        override fun onSchedules(entries: List<Pair<String, CardScheduleDto>>) =
                            offer(queue, StreamEvent.Schedules(entries))
                        override fun onAttempts(attempts: List<AttemptDto>) =
                            offer(queue, StreamEvent.Attempts(attempts))
                    }, session.batchSize)
                }
                tailRef.set(tail)
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                offer(queue, StreamEvent.End)
            }
        }, "import-stream")
        parser.isDaemon = true
        parser.start()

        try {
            while (true) {
                val event = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (event == null) {
                    // The parser died without its End sentinel (interrupted); stop.
                    if (!parser.isAlive) break
                    continue
                }
                when (event) {
                    is StreamEvent.Paper -> session.onPaper(event.header)
                    is StreamEvent.Category -> session.onCategory(event.category)
                    is StreamEvent.Questions -> session.onQuestions(event.categoryId, event.questions)
                    is StreamEvent.Bookmarks -> session.onBookmarks(event.ids)
                    is StreamEvent.Schedules -> session.onSchedules(event.entries)
                    is StreamEvent.Attempts -> session.onAttempts(event.attempts)
                    StreamEvent.End -> break
                }
            }
        } finally {
            // The consumer is leaving, normally or by throwing — the parser
            // must stop, not keep chewing through the rest of the file.
            // Interrupt alone re-arms on every dispatch and a blocked read
            // ignores it entirely; closing the source turns the parser's
            // next read into an IOException and ends the parse.
            cancelled.set(true)
            parser.interrupt()
            inputRef.get()?.close()
            parser.join(2000)
        }
        errorRef.get()?.let { throw it }
        tailRef.get()?.let { session.finish(it) }
        return session.report()
    }

    private sealed interface StreamEvent {
        class Paper(val header: LegacyParser.PaperHeader) : StreamEvent
        class Category(val category: CategoryDto) : StreamEvent
        class Questions(val categoryId: String, val questions: List<QuestionDto>) : StreamEvent
        class Bookmarks(val ids: List<String>) : StreamEvent
        class Schedules(val entries: List<Pair<String, CardScheduleDto>>) : StreamEvent
        class Attempts(val attempts: List<AttemptDto>) : StreamEvent
        object End : StreamEvent
    }
}
