package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.getByCategoriesChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getHashInputsByCategoriesChunked
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

    /**
     * The resolved scheduling model for one paper: the question ids (with
     * their stored hashes for serve-time edit checks) and every card's
     * current schedule under one config. Built once, then reused by every
     * pull and badge read until the scheduling inputs change — the same
     * re-read-on-write correctness today's per-call build bought, without
     * paying a whole-paper price per graded card.
     */
    private class ResolvedStudy(
        val paperId: String,
        val config: com.mcqapp.domain.SchedulerConfig,
        /** Authored order; the queue's fresh pool iterates this. */
        val ids: MutableList<String>,
        /** questionId -> content hash as of the build, for serve-time checks. */
        val hashes: Map<String, String>,
        /** Question's resolved schedule; grades update it in place. */
        val states: MutableMap<String, com.mcqapp.domain.CardState>
    )

    @Volatile
    private var resolved: ResolvedStudy? = null

    init {
        // Any write to the scheduling inputs drops the build; the next read
        // resolves afresh. Quadra: questions carry the hashes, categories
        // the order, card_state the schedules. Robolectric/plain-JVM stores
        // without a tracker lose only the cache, nothing else.
        //
        // Registration opens a connection through Room's
        // runBlockingUninterruptible, so it must not run on the thread that
        // calls the repository constructor: on the main thread it blocks the
        // UI, and under Robolectric's paused looper it can deadlock outright.
        // The cache is optional, so attach off-thread and drop any build that
        // raced ahead of the observer.
        val observer = object : androidx.room.InvalidationTracker.Observer(
            "questions", "categories", "card_state", "papers"
        ) {
            override fun onInvalidated(tables: Set<String>) {
                resolved = null
            }
        }
        Thread({
            try {
                db.invalidationTracker.addObserver(observer)
                resolved = null
            } catch (e: Exception) {
                Logger.w("REPO", "study resolution cache unavailable: ${e.message}")
            }
        }, "study-cache-observer").apply { isDaemon = true }.start()
    }

    private suspend fun resolvedFor(paperId: String): ResolvedStudy {
        val config = settings.schedulerConfigNow()
        resolved?.takeIf { it.paperId == paperId && it.config == config }?.let { return it }
        val inputs = studyInputs(paperId)
        val states = resolveStudyStates(paperId, inputs, config)
        return ResolvedStudy(
            paperId = paperId,
            config = config,
            ids = inputs.map { it.id }.toMutableList(),
            hashes = inputs.associate { it.id to it.contentHash },
            states = states.toMutableMap()
        ).also { resolved = it }
    }

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
        val resolved = resolvedFor(paperId)
        if (resolved.ids.isEmpty()) return emptyList()
        val pools = com.mcqapp.domain.Study.pools(
            fsrs(resolved.config),
            resolved.ids,
            resolved.states,
            now,
            resolved.config.introduceNewCards
        )
        val queue = pools.learning + pools.reviews + pools.fresh
        Logger.i(
            "REPO",
            "getStudyQueue($paperId): ${resolved.ids.size} questions, " +
                "learning=${pools.learning.size} due=${pools.reviews.size} " +
                "new=${pools.fresh.size}"
        )
        return if (leechesOnly) {
            // The drill is an explicit request for problem questions, so its
            // cards wear the leech label however they were pulled.
            com.mcqapp.domain.Study.onlyLeeches(queue)
                .map { it.copy(reason = com.mcqapp.domain.StudyReason.LEECH) }
        } else {
            queue
        }
    }

    /**
     * The card a session should pull next, or null when nothing is eligible —
     * the same pools the badge counts, so the empty state and the pull agree.
     */
    suspend fun getNextStudyCard(
        paperId: String,
        reviewsSinceNew: Int = 0,
        now: Long = System.currentTimeMillis()
    ): com.mcqapp.domain.StudyCard? {
        // The pull runs after every graded card; the resolved model is read
        // once and shared with the badge, so the pull neither loads option
        // text, the answer key, nor a whole-paper card/ ref read per call.
        val resolved = resolvedFor(paperId)
        val config = resolved.config
        val selected = com.mcqapp.domain.Study.selectNext(
            fsrs(config),
            resolved.ids,
            resolved.states,
            now,
            config.studyMode,
            config.introduceNewCards,
            reviewsSinceNew
        ) ?: return null
        return refetchedOnServe(resolved, selected)
    }

    /**
     * Serve-time checks for one card, against a single row: a vanished
     * question leaves the model, and content edited since its schedule was
     * written resets the card exactly as the whole-paper resolution does.
     * Legacy shape — the reset is persisted so it is not repeated.
     */
    private suspend fun refetchedOnServe(
        resolved: ResolvedStudy,
        card: com.mcqapp.domain.StudyCard
    ): com.mcqapp.domain.StudyCard {
        val storedHash = db.questionDao().getHashById(card.questionId)
        if (storedHash == null) {
            // The question was deleted while the model was alive; a dead id
            // must not re-serve every pull.
            resolved.states.remove(card.questionId)
            resolved.ids.remove(card.questionId)
            return card
        }
        val buildHash = resolved.hashes[card.questionId]
        val state = resolved.states[card.questionId]
        if (state != null && buildHash != null && buildHash != storedHash) {
            // The stored hash described other text: schedule the card over.
            val scheduler = fsrs(resolved.config)
            val reset = scheduler.initial(card.questionId)
            db.cardStateDao().upsert(reset.toEntity(resolved.paperId, storedHash))
            resolved.states[card.questionId] = reset
            Logger.i(
                "REPO",
                "resetCard(${card.questionId}) on serve: content edited"
            )
            return com.mcqapp.domain.StudyCard(
                card.questionId,
                com.mcqapp.domain.StudyReason.NEW,
                reset
            )
        }
        return card
    }

    /**
     * Per-pool eligibility counts for the pull path: what a session would
     * serve right now and why. The badge reads the same pools.
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
        val resolved = resolvedFor(paperId)
        if (resolved.ids.isEmpty()) return com.mcqapp.domain.StudyEligibility()
        return com.mcqapp.domain.Study.eligibility(
            fsrs(resolved.config),
            resolved.ids,
            resolved.states,
            now,
            introduceNewCards ?: resolved.config.introduceNewCards
        )
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
     * included. The hash is read from the question row's own column, which
     * every save keeps current, so no option or element is ever loaded: a
     * 169k-question paper has three quarters of a million option rows, and
     * materialising them to re-derive a stored value is what used to OOM.
     */
    private suspend fun studyInputs(paperId: String): List<StudyInput> {
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        if (categoryIds.isEmpty()) return emptyList()
        return db.questionDao().getHashInputsByCategoriesChunked(categoryIds)
            .map { StudyInput(id = it.id, contentHash = it.contentHash) }
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
        // One solved model shared with the pull and the queue, so the badge
        // can never disagree with what either would offer.
        val resolved = resolvedFor(paperId)
        if (resolved.ids.isEmpty()) return StudyCounts()
        // The same pools the study pull reads, so the badge can never
        // advertise a card the session then refuses to serve.
        val e = com.mcqapp.domain.Study.eligibility(
            fsrs(resolved.config),
            resolved.ids,
            resolved.states,
            now,
            resolved.config.introduceNewCards
        )
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
        // One graded card: the shared model is updated in place instead of
        // being whole-paper rebuilt by the next caller.
        resolved?.takeIf { it.paperId == paperId }?.states?.put(questionId, next)
        Logger.i(
            "REPO",
            "recordStudyReview($questionId, $grade): interval=${next.intervalDays}d, " +
                "state=${next.state}, stability=${next.stability}, " +
                "reps=${next.reps}, lapses=${next.lapses}, leech=${next.leech}"
        )
        return next
    }
}
