package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.getCountersByIdsChunked
import com.mcqapp.data.local.getGuessCountsByIdsChunked
import com.mcqapp.data.local.getSignalRowsByCategoriesChunked
import com.mcqapp.data.local.getSignalsByPaperChunked
import com.mcqapp.domain.CardState
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.signals.CardSignal
import com.mcqapp.domain.signals.CounterSet
import com.mcqapp.domain.signals.QuestionSignalInput
import com.mcqapp.domain.signals.QuestionSignals
import com.mcqapp.domain.signals.Signals

/**
 * Reads the per-question evidence a focus selector needs (stats.md §9.1/§9.2).
 *
 * The store is deliberately read-only: it joins the light identity columns with
 * `question_stats`, `card_state` and `review_log`, and never writes Study state.
 * A missing counter row, card or guess sample becomes the neutral prior in
 * [QuestionSignals], not a zero that would look like perfect performance.
 *
 * Retention is computed with the same fitted weights and target retention the
 * study scheduler uses ([SettingsStore.schedulerConfigNow]), so a signal never
 * describes a different model than the one that will schedule the card.
 */
internal class SignalStore(private val db: AppDatabase, private val settings: SettingsStore) {

    /**
     * One signal per question in [scopeIds], or the whole paper when the scope
     * is empty. [now] decides at-risk; the caller passes one instant for the
     * whole load so every question is judged against the same clock.
     */
    suspend fun signals(
        paperId: String,
        scopeIds: List<String>,
        now: Long
    ): List<Signals> {
        val questions = if (scopeIds.isEmpty()) {
            db.questionDao().getSignalRowsForPaper(paperId)
        } else {
            db.questionDao().getSignalRowsByCategoriesChunked(scopeIds)
        }
        if (questions.isEmpty()) return emptyList()

        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(
            desiredRetention = config.desiredRetention,
            config = config
        )
        val ids = questions.map { it.questionId }
        val counters = db.questionStatsDao()
            .getCountersByIdsChunked(ids)
            .associateBy { it.questionId }
        // Scoped by paper: a question id can exist in two papers, and the
        // signals of one must never read the other's card state.
        val cards = db.cardStateDao()
            .getSignalsByPaperChunked(paperId, ids)
            .associateBy { it.questionId }
        val guesses = db.reviewLogDao()
            .getGuessCountsByIdsChunked(ids)
            .associateBy { it.questionId }

        return questions.map { question ->
            val counter = counters[question.questionId]
            val card = cards[question.questionId]
            val guess = guesses[question.questionId]
            val contentHashMatches = card != null && card.contentHash == question.contentHash
            val state = card?.let {
                CardState(
                    questionId = it.questionId,
                    // Copied so a card with memory but no review timestamp
                    // (an imported card) reports its real elapsed time in the
                    // signal retention instead of a clamped one day.
                    intervalDays = it.intervalDays,
                    dueAt = it.dueAt,
                    reps = it.reviewCount,
                    lapses = it.lapses,
                    leech = it.leech,
                    lastReviewedAt = it.lastReviewedAt,
                    difficulty = it.difficulty,
                    stability = it.stability,
                    stabilityFast = it.stabilityFast,
                    state = it.cardState,
                    reviewCount = it.reviewCount
                )
            }
            val retention = state?.let { scheduler.retention(it, now) }
            QuestionSignals.of(
                input = QuestionSignalInput(
                    questionId = question.questionId,
                    passageId = question.passageId,
                    categoryId = question.categoryId,
                    categoryTitle = question.categoryTitle,
                    authoredDifficulty = Difficulty.fromLabel(question.difficulty),
                    correct = counter?.correct ?: 0,
                    wrong = counter?.wrong ?: 0,
                    skip = counter?.skip ?: 0,
                    repCorrect = counter?.repCorrect ?: 0,
                    repWrong = counter?.repWrong ?: 0,
                    repSkip = counter?.repSkip ?: 0,
                    guesses = guess?.guesses ?: 0,
                    guessFlags = guess?.flags ?: 0,
                    card = card?.let {
                        CardSignal(
                            difficulty = it.difficulty,
                            stability = it.stability,
                            reviewCount = it.reviewCount,
                            lapses = it.lapses,
                            leech = it.leech,
                            dueAt = it.dueAt,
                            lastReviewedAt = it.lastReviewedAt,
                            contentHashMatches = contentHashMatches,
                            state = it.cardState
                        )
                    },
                    retention = retention
                ),
                counterSet = CounterSet.PERSONAL,
                now = now
            )
        }
    }
}
