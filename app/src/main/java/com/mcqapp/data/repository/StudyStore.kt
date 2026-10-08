package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.getByCategoriesChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getGradedResultsForQuestionsChunked
import com.mcqapp.data.io.ContentHash
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger

/** Spaced repetition badges for a paper in the library list. */
data class StudyCounts(
    val due: Int = 0,
    val leeches: Int = 0,
    val fresh: Int = 0,
    val dueWaiting: Int = 0,
    val freshWaiting: Int = 0,
    val newBlockedByReviewLimit: Boolean = false
) {
    /** Cards the daily limits hold back from the next session. */
    val waiting: Int get() = dueWaiting + freshWaiting

    val isEmpty: Boolean get() = due == 0 && leeches == 0 && fresh == 0
}

/**
 * Spaced repetition: card state resolution and seeding, the study queue and
 * the library badge counts. Split out of [McqRepository]; it reads questions
 * through [QuestionStore] and the scheduler options through [SettingsStore],
 * so the badge and the queue can never disagree about either.
 */
internal class StudyStore(
    private val db: AppDatabase,
    private val mapper: QuestionContentMapper,
    private val questions: QuestionStore,
    private val settings: SettingsStore
) {

    private fun CardStateEntity.toDomain() = com.mcqapp.domain.CardState(
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt
    )

    private fun com.mcqapp.domain.CardState.toEntity(paperId: String, hash: String) =
        CardStateEntity(
            paperId = paperId,
            questionId = questionId,
            ease = ease,
            intervalDays = intervalDays,
            dueAt = dueAt,
            reps = reps,
            lapses = lapses,
            leech = leech,
            lastReviewedAt = lastReviewedAt,
            contentHash = hash
        )

    /**
     * Today's study queue for a paper, seeding any card that has never been
     * studied from existing attempt history so an existing install starts warm
     * instead of treating every question as new.
     */
    suspend fun getStudyQueue(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        newLimit: Int? = null,
        leechesOnly: Boolean = false
    ): List<com.mcqapp.domain.StudyCard> {
        val questionList = questions.getQuestionsForPaper(paperId)
        if (questionList.isEmpty()) return emptyList()
        val config = settings.schedulerConfigNow()
        val states = resolveStudyStates(
            paperId,
            questionList.map { StudyInput(it.id, mapper.contentHashOf(it)) },
            config
        )
        val selection = com.mcqapp.domain.Study.selection(
            com.mcqapp.domain.Sm2Scheduler(config),
            questionList.map { it.id },
            states,
            now,
            newLimit ?: config.newLimit,
            config.reviewLimit,
            config.newCardsIgnoreReviewLimit
        )
        Logger.i(
            "REPO",
            "getStudyQueue($paperId): ${questionList.size} questions, " +
                "serving due=${selection.due.size} tricky=${selection.leeches.size} " +
                "new=${selection.fresh.size}, waiting=${selection.waiting}"
        )
        val queue = selection.queue
        if (selection.dueWaiting > 0 || selection.newBlockedByReviewLimit) {
            Logger.i(
                "REPO",
                "getStudyQueue($paperId): daily limits applied, " +
                    "${selection.dueWaiting} due held back, ${selection.freshWaiting} new held back"
            )
        }
        return if (leechesOnly) com.mcqapp.domain.Study.onlyLeeches(queue) else queue
    }

    /**
     * What scheduling actually needs about a question: its id and the hash of
     * its content. Both are derived from the stored elements, so the counts on
     * the library screen no longer have to load every option and every answer
     * key just to count them.
     */
    private data class StudyInput(val id: String, val contentHash: String)

    /**
     * Ids and content hashes for a paper's questions, nested categories
     * included, from the questions and options rows alone.
     */
    private suspend fun studyInputs(paperId: String): List<StudyInput> {
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        if (categoryIds.isEmpty()) return emptyList()
        val questionEntities = db.questionDao().getByCategoriesChunked(categoryIds)
        if (questionEntities.isEmpty()) return emptyList()
        val optionsByQuestion = db.optionDao()
            .getForQuestionsChunked(questionEntities.map { it.id })
            .groupBy { it.questionId }
        return questionEntities.map { entity ->
            val options = optionsByQuestion[entity.id].orEmpty()
            StudyInput(
                id = entity.id,
                contentHash = ContentHash.of(
                    entity.text.parseContentElements(mapper.json).textContent,
                    options.map { it.text.parseContentElements(mapper.json).textContent },
                    options.map { it.image }
                )
            )
        }
    }

    /**
     * Resolves every question's card state, seeding anything missing from
     * attempt history and resetting questions whose text has changed. Both the
     * queue and the library badges go through here, so the badge can never
     * disagree with what the queue would actually offer.
     */
    private suspend fun resolveStudyStates(
        paperId: String,
        questionInputs: List<StudyInput>,
        config: com.mcqapp.domain.SchedulerConfig
    ): Map<String, com.mcqapp.domain.CardState> {
        val scheduler = com.mcqapp.domain.Sm2Scheduler(config)
        val stored = db.cardStateDao().getByPaper(paperId).associate { it.questionId to it }
        val history = historySignalsFor(paperId, questionInputs.map { it.id }, config)
        val states = mutableMapOf<String, com.mcqapp.domain.CardState>()
        val seeded = mutableListOf<CardStateEntity>()
        for (q in questionInputs) {
            val existing = stored[q.id]
            if (existing != null) {
                // A question whose text or options changed is scheduled again
                // from scratch: the old interval describes memory of other text.
                if (existing.contentHash != q.contentHash) {
                    val reset = scheduler.initial(q.id)
                    states[q.id] = reset
                    // Persist the new hash, otherwise the edit looks stale again
                    // on the next load and the card is reset every time.
                    seeded += reset.toEntity(paperId, q.contentHash)
                } else {
                    states[q.id] = existing.toDomain()
                }
            } else {
                val rebuilt = history[q.id]?.let {
                    com.mcqapp.domain.Study.rebuild(scheduler, q.id, it)
                } ?: scheduler.initial(q.id)
                states[q.id] = rebuilt
                seeded += rebuilt.toEntity(paperId, q.contentHash)
            }
        }
        if (seeded.isNotEmpty()) {
            // One transaction: this runs from a badge read as well as from
            // getStudyQueue, and without it the seeding was N separate
            // transactions that two coroutines could interleave.
            db.withTransaction { seeded.forEach { db.cardStateDao().upsert(it) } }
            Logger.i("REPO", "seeded ${seeded.size} card_state rows for $paperId")
        }
        return states
    }

    /** Due / new / leech counts for a paper's library badge. */
    suspend fun getStudyCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): StudyCounts {
        // Counted from ids and content hashes only. The library re-reads these
        // on every resume, and loading every question with its options and
        // answer key just to add up three numbers made a large library crawl.
        val inputs = studyInputs(paperId)
        if (inputs.isEmpty()) return StudyCounts()
        val config = settings.schedulerConfigNow()
        val states = resolveStudyStates(paperId, inputs, config)
        // The same selection the study session is built from, so the badge can
        // never advertise a card the session then refuses to serve.
        val selection = com.mcqapp.domain.Study.selection(
            com.mcqapp.domain.Sm2Scheduler(config),
            inputs.map { it.id },
            states,
            now,
            config.newLimit,
            config.reviewLimit,
            config.newCardsIgnoreReviewLimit
        )
        return StudyCounts(
            due = selection.due.size,
            leeches = selection.leeches.size,
            fresh = selection.fresh.size,
            dueWaiting = selection.dueWaiting,
            freshWaiting = selection.freshWaiting,
            newBlockedByReviewLimit = selection.newBlockedByReviewLimit
        )
    }

    /** The stored schedule for a card, or null if it has never been studied. */
    suspend fun cardState(
        paperId: String,
        questionId: String
    ): com.mcqapp.domain.CardState? = db.cardStateDao().get(paperId, questionId)?.toDomain()

    /** Applies one grade to a question's card and persists the new schedule. */
    suspend fun recordStudyReview(
        paperId: String,
        questionId: String,
        grade: com.mcqapp.domain.ReviewGrade,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.CardState {
        val question = questions.getQuestion(questionId)
        val scheduler = com.mcqapp.domain.Sm2Scheduler(settings.schedulerConfigNow())
        val existing = db.cardStateDao().get(paperId, questionId)?.toDomain()
            ?: scheduler.initial(questionId)
        val next = scheduler.next(existing, grade, now)
        db.cardStateDao().upsert(
            next.toEntity(paperId, question?.let { mapper.contentHashOf(it) } ?: "")
        )
        Logger.i(
            "REPO",
            "recordStudyReview($questionId, $grade): interval=${next.intervalDays}d, " +
                "reps=${next.reps}, lapses=${next.lapses}, leech=${next.leech}"
        )
        return next
    }

    /**
     * Graded history for the given questions, newest last. Rows with no
     * selection or no answer key carry no memory signal, so they are skipped.
     */
    private suspend fun historySignalsFor(
        paperId: String,
        questionIds: List<String>,
        config: com.mcqapp.domain.SchedulerConfig = com.mcqapp.domain.SchedulerConfig()
    ): Map<String, List<com.mcqapp.domain.ReviewSignal>> {
        if (questionIds.isEmpty()) return emptyMap()
        val attemptsById = db.attemptDao().getByPaper(paperId).associateBy { it.id }
        if (attemptsById.isEmpty()) return emptyMap()
        val signals = mutableMapOf<String, MutableList<com.mcqapp.domain.ReviewSignal>>()
        // Scoped to this paper and these question ids in SQL, rather than
        // loading every attempt and every result row in the database.
        val rows = db.attemptDao().getGradedResultsForQuestionsChunked(paperId, questionIds)
        for (row in rows) {
            val attempt = attemptsById[row.attemptId] ?: continue
            val grade = com.mcqapp.domain.Study.inferGrade(
                row.isCorrect, row.dwellSeconds, config = config
            )
            signals.getOrPut(row.questionId) { mutableListOf() }
                .add(com.mcqapp.domain.ReviewSignal(row.questionId, grade, attempt.finishedAt))
        }
        return signals
    }
}
