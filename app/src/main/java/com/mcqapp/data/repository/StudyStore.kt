package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
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
 * Spaced repetition: the study queue, the library badge counts and the
 * schedules behind them. Split out of [McqRepository]; it reads questions
 * through [QuestionStore] and the scheduler options through [SettingsStore],
 * so the badge and the queue can never disagree about either.
 *
 * Every read here is scoped to the rows that describe study history — the
 * schedules that are due, the first unanswered question — instead of
 * resolving every question's card state. A whole-paper model costs hundreds
 * of megabytes on a paper of a million questions, and resolving one also
 * wrote a schedule row for every question it had not seen, so merely opening
 * the library wrote a million rows before it could show a badge.
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
     * Every eligible card in study order — the tricky drill's source. The main
     * session no longer prebuilds a queue; it pulls one card at a time through
     * [getNextStudyCard], which reads the same pools this assembles. Cards the
     * store has never seen start new: attempt history no longer re-seeds
     * schedules (it would resurrect the SM-2 states the FSRS switch wiped, and
     * its inferred grades were a guess at the learner's memory, not a record
     * of it). Imports remain the only warm-start vectors.
     */
    suspend fun getStudyQueue(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        leechesOnly: Boolean = false
    ): List<com.mcqapp.domain.StudyCard> {
        val config = settings.schedulerConfigNow()
        resetEdited(paperId, config)
        val pools = poolsOf(config, dueCards(paperId, now), now)
        if (leechesOnly) {
            // The drill is an explicit request for problem questions, so its
            // cards wear the leech label however they were pulled. A new card
            // is never a leech (a leech has lapsed at least once), so the due
            // pools are the whole of it — bounded by the schedules on file
            // rather than by the paper, which is what makes the drill usable
            // on a paper that has one.
            return com.mcqapp.domain.Study.onlyLeeches(pools.learning + pools.reviews)
                .map { it.copy(reason = com.mcqapp.domain.StudyReason.LEECH) }
        }
        val fresh = if (config.introduceNewCards) {
            val scheduler = fsrs(config)
            db.questionDao().newQuestionsForPaper(paperId).map {
                com.mcqapp.domain.StudyCard(
                    it,
                    com.mcqapp.domain.StudyReason.NEW,
                    scheduler.initial(it)
                )
            }
        } else {
            emptyList()
        }
        Logger.i(
            "REPO",
            "getStudyQueue($paperId): learning=${pools.learning.size} " +
                "due=${pools.reviews.size} new=${fresh.size} leechesOnly=$leechesOnly"
        )
        return pools.learning + pools.reviews + fresh
    }

    /**
     * The card a session should pull next, or null when nothing is eligible —
     * the same pools the badge counts, so the empty state and the pull agree.
     *
     * The pull runs after every graded card, so it reads the schedules that
     * are due and the first unanswered question rather than a model of the
     * paper: the cost is the paper's study history, not its length.
     */
    suspend fun getNextStudyCard(
        paperId: String,
        reviewsSinceNew: Int = 0,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.StudyCard? {
        val config = settings.schedulerConfigNow()
        resetEdited(paperId, config)
        val pools = poolsOf(config, dueCards(paperId, now), now)
        return com.mcqapp.domain.Study.pickNext(
            pools.learning,
            pools.reviews,
            firstNewCard(config, paperId),
            config.studyMode,
            reviewsSinceNew
        )
    }

    /**
     * The paper's schedules that are due now and still describe their
     * question, in authored order. Every card the paper has already introduced
     * is here; a row whose hash moved on is left out, because an edited
     * question is new again and the new-card query picks it up.
     */
    private suspend fun dueCards(paperId: String, now: Long): List<CardStateEntity> =
        db.cardStateDao().dueForPaper(paperId, now)

    /**
     * Writes out the reset for questions edited since they were scheduled, so
     * the stored row stops describing text the learner never saw and every
     * reader of it — the statistics, an export, the badge's own count — agrees
     * with what the session serves. Bounded by how many questions were edited,
     * which is what makes persisting it affordable.
     *
     * A question nobody has studied is deliberately *not* seeded a row: that is
     * one row per question of the paper, which is what made a badge read write
     * a million rows.
     */
    private suspend fun resetEdited(
        paperId: String,
        config: com.mcqapp.domain.SchedulerConfig
    ) {
        val stale = db.cardStateDao().staleForPaper(paperId)
        if (stale.isEmpty()) return
        val scheduler = fsrs(config)
        db.withTransaction {
            stale.forEach {
                db.cardStateDao().upsert(
                    scheduler.initial(it.id).toEntity(paperId, it.contentHash)
                )
            }
        }
        Logger.i("REPO", "resetEdited($paperId): ${stale.size} card(s) scheduled again")
    }

    /** The learning and review pools of [due], read through the pure pools. */
    private fun poolsOf(
        config: com.mcqapp.domain.SchedulerConfig,
        due: List<CardStateEntity>,
        now: Long
    ): com.mcqapp.domain.Study.CandidatePools = com.mcqapp.domain.Study.pools(
        fsrs(config),
        due.map { it.questionId },
        due.associate { it.questionId to it.toDomain() },
        now,
        config.introduceNewCards
    )

    /**
     * The first new card in authored order, or null when the paper has none
     * left or new cards are switched off. The session serves new cards one at
     * a time, so the first of them stands in for the pool a whole-paper model
     * would have built.
     */
    private suspend fun firstNewCard(
        config: com.mcqapp.domain.SchedulerConfig,
        paperId: String
    ): com.mcqapp.domain.StudyCard? {
        if (!config.introduceNewCards) return null
        val id = db.questionDao().firstNewQuestionForPaper(paperId) ?: return null
        return com.mcqapp.domain.StudyCard(
            id,
            com.mcqapp.domain.StudyReason.NEW,
            fsrs(config).initial(id)
        )
    }

    /**
     * Per-pool eligibility counts for the pull path: what a session would
     * serve right now and why. The badge reads the same numbers, so the empty
     * state can never promise a card the pull refuses.
     */
    suspend fun getStudyEligibility(
        paperId: String,
        now: Long = System.currentTimeMillis(),
        /**
         * Overrides the stored introduce-new-cards toggle. The empty state asks
         * the opposite question ("would there be new cards if it were on?"),
         * so it can offer exactly the flip. Null reads the config.
         */
        introduceNewCards: Boolean? = null
    ): com.mcqapp.domain.StudyEligibility {
        val config = settings.schedulerConfigNow()
        return counts(paperId, now, config, introduceNewCards ?: config.introduceNewCards)
    }

    /**
     * The four pool sizes, counted from the rows instead of by resolving every
     * question's card state. [com.mcqapp.domain.Study.pools] classifies a card
     * by its stored schedule and by whether its hash still matches its
     * question, and both are predicates SQL can apply — see
     * [com.mcqapp.data.local.CardStateDao.countsForPaper].
     */
    private suspend fun counts(
        paperId: String,
        now: Long,
        config: com.mcqapp.domain.SchedulerConfig,
        introduceNewCards: Boolean
    ): com.mcqapp.domain.StudyEligibility {
        val questions = db.questionDao().countForPaper(paperId)
        if (questions == 0) return com.mcqapp.domain.StudyEligibility()
        val rows = db.cardStateDao().countsForPaper(paperId, now)
        // Fresh is everything the session would serve as new: the questions
        // with no schedule at all, plus the stored rows it would treat as new
        // (still new, or edited since) — that is, what is left after the rows
        // that describe an introduced card.
        val fresh = if (introduceNewCards) questions - (rows.storedRows - rows.newish) else 0
        return com.mcqapp.domain.StudyEligibility(
            learning = rows.learning,
            due = rows.due,
            leeches = rows.leeches,
            fresh = fresh
        )
    }

    /** Due / new / leech counts for a paper's library badge. */
    suspend fun getStudyCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): StudyCounts {
        val config = settings.schedulerConfigNow()
        // The same pools the study pull reads, so the badge can never
        // advertise a card the session then refuses to serve.
        val e = counts(paperId, now, config, config.introduceNewCards)
        return StudyCounts(
            due = e.learning + e.due,
            leeches = e.leeches,
            fresh = e.fresh
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
