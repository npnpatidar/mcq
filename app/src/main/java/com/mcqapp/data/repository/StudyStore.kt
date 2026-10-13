package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.getByCategoriesChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.io.ContentHash
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger

/** Spaced repetition badges for a paper in the library list. */
data class StudyCounts(
    val due: Int = 0,
    val leeches: Int = 0,
    val fresh: Int = 0
) {
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

    /** The FSRS scheduler the whole store grades and counts with. */
    private fun fsrs(config: com.mcqapp.domain.SchedulerConfig) =
        com.mcqapp.domain.FsrsScheduler(
            desiredRetention = config.desiredRetention,
            config = config
        )

    private fun CardStateEntity.toDomain() = com.mcqapp.domain.CardState(
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt,
        difficulty = difficulty,
        stability = stability,
        state = cardState,
        reviewCount = reviewCount
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
            contentHash = hash,
            difficulty = difficulty,
            stability = stability,
            cardState = state,
            reviewCount = reviewCount
        )

    /**
     * Today's study queue for a paper. Cards the store has never seen start
     * new: attempt history no longer re-seeds schedules (it would resurrect
     * the SM-2 states the FSRS switch wiped, and its inferred grades were a
     * guess at the learner's memory, not a record of it). Imports remain the
     * only warm-start vectors.
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
            fsrs(config),
            questionList.map { it.id },
            states,
            now,
            // No daily caps: badges and the queue describe every eligible card.
            // The optional newLimit narrows the stream for a drill or preview.
            newLimit ?: Int.MAX_VALUE,
            Int.MAX_VALUE,
            false
        )
        Logger.i(
            "REPO",
            "getStudyQueue($paperId): ${questionList.size} questions, " +
                "serving due=${selection.due.size} tricky=${selection.leeches.size} " +
                "new=${selection.fresh.size}"
        )
        val queue = selection.queue
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
                    options.map { it.image },
                    entity.passageId
                )
            )
        }
    }

    /**
     * Resolves every question's card state, resetting questions whose text has
     * changed. Both the queue and the library badges go through here, so the
     * badge can never disagree with what the queue would actually offer.
     */
    private suspend fun resolveStudyStates(
        paperId: String,
        questionInputs: List<StudyInput>,
        config: com.mcqapp.domain.SchedulerConfig
    ): Map<String, com.mcqapp.domain.CardState> {
        val scheduler = fsrs(config)
        val stored = db.cardStateDao().getByPaper(paperId).associate { it.questionId to it }
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
                val fresh = scheduler.initial(q.id)
                states[q.id] = fresh
                seeded += fresh.toEntity(paperId, q.contentHash)
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
            fsrs(config),
            inputs.map { it.id },
            states,
            now,
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            false
        )
        return StudyCounts(
            due = selection.due.size,
            leeches = selection.leeches.size,
            fresh = selection.fresh.size
        )
    }

    /** The stored schedule for a card, or null if it has never been studied. */
    suspend fun cardState(
        paperId: String,
        questionId: String
    ): com.mcqapp.domain.CardState? = db.cardStateDao().get(paperId, questionId)?.toDomain()

    /**
     * Applies one grade to a question's card and persists it: the new schedule
     * and the immutable review_log row are written in one transaction, so a
     * crash can never leave a review that history does not know about or a log
     * row the card state disagrees with (spec §6).
     *
     * [dwellSeconds] is the time spent on the card, kept for the log and the
     * future optimizer; null when the caller has no trustworthy measurement.
     */
    suspend fun recordStudyReview(
        paperId: String,
        questionId: String,
        grade: com.mcqapp.domain.ReviewGrade,
        dwellSeconds: Long? = null,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.CardState {
        val question = questions.getQuestion(questionId)
        val scheduler = fsrs(settings.schedulerConfigNow())
        val existing = db.cardStateDao().get(paperId, questionId)?.toDomain()
        val next = scheduler.next(existing ?: scheduler.initial(questionId), grade, now)
        db.withTransaction {
            db.cardStateDao().upsert(
                next.toEntity(paperId, question?.let { mapper.contentHashOf(it) } ?: "")
            )
            db.reviewLogDao().insert(
                com.mcqapp.data.local.ReviewLogEntity(
                    paperId = paperId,
                    questionId = questionId,
                    rating = grade.ordinal + 1,
                    reviewedAt = now,
                    elapsedSeconds = dwellSeconds,
                    stateBefore = existing?.state?.name,
                    stabilityAfter = next.stability,
                    difficultyAfter = next.difficulty,
                    intervalDaysAfter = next.intervalDays
                )
            )
        }
        Logger.i(
            "REPO",
            "recordStudyReview($questionId, $grade): interval=${next.intervalDays}d, " +
                "state=${next.state}, stability=${next.stability}, " +
                "reps=${next.reps}, lapses=${next.lapses}, leech=${next.leech}"
        )
        return next
    }
}
