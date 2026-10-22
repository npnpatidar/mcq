package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.chunks
import com.mcqapp.data.local.countForCategoriesChunked
import com.mcqapp.data.local.countsForCategoriesChunked
import com.mcqapp.data.local.dueForCategoriesChunked
import com.mcqapp.data.local.firstDueLearningForCategoriesChunked
import com.mcqapp.data.local.firstNewQuestionForCategoriesChunked
import com.mcqapp.data.local.newQuestionsForCategoriesChunked
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
        leechesOnly: Boolean = false,
        categoryIds: List<String> = emptyList()
    ): List<com.mcqapp.domain.StudyCard> {
        val config = settings.schedulerConfigNow()
        resetEdited(paperId, config)
        val scope = scopeIds(categoryIds)
        val pools = poolsOf(config, dueCards(paperId, now, scope), now)
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
            newQuestions(paperId, scope).map {
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
        now: Long = System.currentTimeMillis(),
        categoryIds: List<String> = emptyList()
    ): com.mcqapp.domain.StudyCard? {
        val config = settings.schedulerConfigNow()
        resetEdited(paperId, config)
        val scheduler = fsrs(config)
        val scope = scopeIds(categoryIds)
        // The pull only ever reads the head of each pool, so each is selected by
        // SQL (`LIMIT 1`) instead of built from every due row. The risk ranking
        // is `Study.pools`' comparator expressed in SQL, so the served card is
        // the one the whole-paper model would have served — without reading the
        // backlog on the calling (main) thread. A scoped pull dispatches to the
        // category-filtered forms; the review head is picked globally across
        // chunks by `Study.bestReview`, the same comparator as the pool.
        val learning = firstDueLearning(paperId, now, scope)?.toDomain()
        val review = bestDueReview(paperId, scope, now, scheduler)?.toDomain()
        return com.mcqapp.domain.Study.pickNext(
            learning = listOfNotNull(
                learning?.let {
                    com.mcqapp.domain.StudyCard(
                        it.questionId,
                        com.mcqapp.domain.StudyReason.LEARNING,
                        it
                    )
                }
            ),
            reviews = listOfNotNull(
                review?.let {
                    com.mcqapp.domain.StudyCard(
                        it.questionId,
                        reviewReason(scheduler, it, now),
                        it
                    )
                }
            ),
            freshFirst = firstNewCard(config, paperId, scope),
            studyMode = config.studyMode,
            reviewsSinceNew = reviewsSinceNew
        )
    }

    /** Why a due review is served: the threshold `Study.pools` labels with. */
    private fun reviewReason(
        scheduler: com.mcqapp.domain.FsrsScheduler,
        state: com.mcqapp.domain.CardState,
        now: Long
    ): com.mcqapp.domain.StudyReason =
        if (scheduler.retention(state, now) < com.mcqapp.domain.Study.LOW_RECALL_RISK_RETENTION) {
            com.mcqapp.domain.StudyReason.LOW_RECALL_RISK
        } else {
            com.mcqapp.domain.StudyReason.DUE
        }

    /**
     * The paper's schedules that are due now and still describe their
     * question, in authored order. Every card the paper has already introduced
     * is here; a row whose hash moved on is left out, because an edited
     * question is new again and the new-card query picks it up. An empty
     * [categoryIds] is the whole paper; otherwise the scoped query is chunked.
     */
    private suspend fun dueCards(
        paperId: String,
        now: Long,
        categoryIds: List<String>
    ): List<CardStateEntity> =
        if (categoryIds.isEmpty()) {
            db.cardStateDao().dueForPaper(paperId, now)
        } else {
            db.cardStateDao().dueForCategoriesChunked(paperId, categoryIds, now)
        }

    /**
     * The scoped query input. Duplicates would double-count in the sum-based
     * aggregates (`countForCategoriesChunked`, `countsForCategoriesChunked`),
     * because every chunk is summed. `CategorySelection.orderedIds` never
     * emits a duplicate, so this guards a hand-built caller rather than fixing
     * a live path; empty still means the whole paper.
     */
    private fun scopeIds(categoryIds: List<String>): List<String> =
        if (categoryIds.isEmpty()) emptyList() else categoryIds.distinct()

    /** The new pool's ids: whole paper, or the scope in chunk order. */
    private suspend fun newQuestions(paperId: String, categoryIds: List<String>): List<String> =
        if (categoryIds.isEmpty()) {
            db.questionDao().newQuestionsForPaper(paperId)
        } else {
            db.questionDao().newQuestionsForCategoriesChunked(paperId, categoryIds)
        }

    /** The first due learning card of the paper or of the scope. */
    private suspend fun firstDueLearning(
        paperId: String,
        now: Long,
        categoryIds: List<String>
    ): CardStateEntity? =
        if (categoryIds.isEmpty()) {
            db.cardStateDao().firstDueLearningForPaper(paperId, now)
        } else {
            db.cardStateDao().firstDueLearningForCategoriesChunked(paperId, categoryIds, now)
        }

    /**
     * The review head: the paper-wide SQL pick, or, for a scope, one winner per
     * chunk and then [com.mcqapp.domain.Study.bestReview] over those winners.
     * [scheduler] is the pull's one config snapshot, so both branches rank with
     * the same scheduler the rest of the pull uses.
     */
    private suspend fun bestDueReview(
        paperId: String,
        categoryIds: List<String>,
        now: Long,
        scheduler: com.mcqapp.domain.FsrsScheduler
    ): CardStateEntity? =
        if (categoryIds.isEmpty()) {
            db.cardStateDao().bestDueReviewForPaper(
                paperId,
                now,
                com.mcqapp.domain.FsrsScheduler.DAY_MS.toDouble()
            )
        } else {
            bestDueReviewForCategoriesChunked(paperId, categoryIds, now, scheduler)
        }

    /**
     * The global review winner across chunks. The SQL retention ranking runs
     * per chunk; the global winner is necessarily the winner inside its own
     * chunk, so the per-chunk winners are the candidates and the shared pools
     * comparator picks between them. The entity is mapped back by id because
     * [com.mcqapp.domain.Study.bestReview] returns a domain card.
     *
     * A cross-chunk exact tie on (retention, dueAt) keeps the winner list's
     * order, whereas the paper-wide SQL would fall through to
     * `(sortOrder, title, q.sortOrder, rowid)`. That differs only on an exact
     * tie at a chunk boundary; this is not claimed as parity.
     */
    private suspend fun bestDueReviewForCategoriesChunked(
        paperId: String,
        categoryIds: List<String>,
        now: Long,
        scheduler: com.mcqapp.domain.FsrsScheduler
    ): CardStateEntity? {
        val dayMs = com.mcqapp.domain.FsrsScheduler.DAY_MS.toDouble()
        val winners = categoryIds.chunks().mapNotNull { chunk ->
            db.cardStateDao().bestDueReviewForCategories(paperId, chunk, now, dayMs)
        }
        if (winners.isEmpty()) return null
        val cards = winners.map { row ->
            val state = row.toDomain()
            com.mcqapp.domain.StudyCard(row.questionId, reviewReason(scheduler, state, now), state)
        }
        val best = com.mcqapp.domain.Study.bestReview(scheduler, cards, now) ?: return null
        return winners.first { it.questionId == best.questionId }
    }

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
        paperId: String,
        categoryIds: List<String>
    ): com.mcqapp.domain.StudyCard? {
        if (!config.introduceNewCards) return null
        val id = if (categoryIds.isEmpty()) {
            db.questionDao().firstNewQuestionForPaper(paperId)
        } else {
            db.questionDao().firstNewQuestionForCategoriesChunked(paperId, categoryIds)
        } ?: return null
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
        introduceNewCards: Boolean? = null,
        categoryIds: List<String> = emptyList()
    ): com.mcqapp.domain.StudyEligibility {
        val config = settings.schedulerConfigNow()
        return counts(
            paperId,
            now,
            config,
            introduceNewCards ?: config.introduceNewCards,
            scopeIds(categoryIds)
        )
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
        introduceNewCards: Boolean,
        categoryIds: List<String>
    ): com.mcqapp.domain.StudyEligibility {
        val questions = if (categoryIds.isEmpty()) {
            db.questionDao().countForPaper(paperId)
        } else {
            db.questionDao().countForCategoriesChunked(paperId, categoryIds)
        }
        if (questions == 0) return com.mcqapp.domain.StudyEligibility()
        val rows = if (categoryIds.isEmpty()) {
            db.cardStateDao().countsForPaper(paperId, now)
        } else {
            db.cardStateDao().countsForCategoriesChunked(paperId, categoryIds, now)
        }
        // Fresh is everything the session would serve as new: the questions
        // with no schedule at all, plus the stored rows it would treat as new
        // (still new, or edited since) — that is, what is left after the rows
        // that describe an introduced card. Summing the three inputs across
        // chunks yields the same expression.
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
        val e = counts(paperId, now, config, config.introduceNewCards, emptyList())
        return StudyCounts(
            due = e.learning + e.due,
            leeches = e.leeches,
            fresh = e.fresh
        )
    }

    /**
     * The per-category form of [getStudyCounts]: each category's *own*
     * scheduling sums and question count, with `fresh` derived by the literal
     * expression [counts] uses (`questions - (storedRows - newish)`, gated by
     * `introduceNewCards`). The subtree rollup and the paper total happen
     * above this — in the domain, over `Paper.categories`.
     *
     * The grouped read's SQL is copied verbatim from
     * [CardStateDao.countsForPaper] (a `categoryId` column and a `GROUP BY`
     * added), so summing the rolled-up totals of the paper's roots equals the
     * badge. That parity is a test, not a promise (D9 of
     * `category_card_counts.md`); one caveat stands: a category unreachable
     * from the tree (dangling `parentId`, a cycle) is counted here but absent
     * from `Paper.categories`, so the rollup then totals less than the badge —
     * the same blind spot `Paper.totalQuestions` has today.
     */
    suspend fun categoryCounts(
        paperId: String,
        now: Long = System.currentTimeMillis()
    ): Map<String, com.mcqapp.domain.CategoryCounts> {
        val config = settings.schedulerConfigNow()
        val questions = db.questionDao().categoryCountsForPaper(paperId)
        val rows = db.cardStateDao().categoryPopulations(paperId, now)
        // The populations read joins questions, so it cannot hold a category
        // the question count lacks; both empty is simply "nothing to show",
        // the same early return the badge's [counts] takes at zero questions.
        if (questions.isEmpty() && rows.isEmpty()) return emptyMap()

        val questionCounts = questions.associate { it.categoryId to it.cnt }
        val populations = rows.associateBy { it.categoryId }
        val out = LinkedHashMap<String, com.mcqapp.domain.CategoryCounts>()
        for (id in questionCounts.keys + populations.keys) {
            val row = populations[id]
            val count = questionCounts[id] ?: 0
            val stored = row?.storedRows ?: 0
            val newish = row?.newish ?: 0
            // No clamp, on purpose: the parity test is what proves the
            // arithmetic, and clamping one side would hide a real divergence.
            val fresh =
                if (config.introduceNewCards) count - (stored - newish) else 0
            out[id] = com.mcqapp.domain.CategoryCounts(
                due = (row?.learning ?: 0) + (row?.due ?: 0),
                fresh = fresh,
                leeches = row?.leeches ?: 0
            )
        }
        return out
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
