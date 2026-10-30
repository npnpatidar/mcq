package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.GuessingData
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.ReviewHistoryEntry
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.WorkloadData

/**
 * Loads what the statistics page needs: the replayed review log, every card's
 * current schedule, and the paper titles the breakdowns group by. The log and
 * the cards are separate reads — the retention tab streams past the log and
 * never builds it, so [loadLibrary] and [loadReviews] are asked for
 * independently. The list-shaped reads are kept whole so the ViewModel can
 * re-slice ranges and scopes in memory without touching the database.
 */
internal class StatsStore(private val db: AppDatabase, private val settings: SettingsStore) {

    /**
     * Everything but the log. A library this size is a fraction of the review
     * log, and every list-based tab slices it in memory per range and scope.
     */
    data class Library(
        val cards: List<CardSnapshot>,
        /** paperId → title. */
        val paperTitleOf: Map<String, String>,
        val config: SchedulerConfig
    )

    suspend fun loadLibrary(): Library {
        val config = settings.schedulerConfigNow()
        return Library(
            cards = loadCards(),
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title },
            config = config
        )
    }

    /**
     * The whole log in one list, for the tabs that slice it in memory. A caller
     * that only needs the retention tab streams instead and never pays for it.
     */
    suspend fun loadReviews(): List<ReplayReview> {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        return replayLog(scheduler)
    }

    /**
     * The retention tab, built while the log streams past instead of from a
     * list of it: pages are replayed straight into
     * [StatsAggregator.RetentionAccumulator], which keeps only per-card state
     * and counters. This tab is the page's memory high-water mark otherwise,
     * because it is the one section that reads the whole log on its own scope
     * as well as on the selected range.
     */
    suspend fun loadRetention(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): RetentionData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.RetentionAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            // One offset per load (the visit's own), not per historical
            // instant: re-bucketing history through travel/DST shifts would
            // move rows between visits. Within an hour of the 04:00 boundary
            // a zone change mis-buckets until the next load — unobservable
            // where the 04:00 day start sits far from a DST jump
            // (consolidated_bugs.md E12).
            zone = DayBoundary.zoneOffset(now),
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title },
            // Only the scoped breakdown labels by category, and the map holds
            // one entry per question of the selected paper — never the whole
            // bank — so the unscoped view skips building it.
            categoryOfQuestion = scopePaperId?.let { loadCategoryOfQuestion(it) } ?: emptyMap()
        )
        replayInto(scheduler, accumulator::add)
        return accumulator.toData()
    }

    /**
     * The overview tab, streamed: the log is replayed and the cards are paged
     * into one [StatsAggregator.OverviewAccumulator], so neither list is ever
     * built. This is the tab the page opens on, so it is the read that decides
     * whether a large library crashes the screen.
     */
    suspend fun loadOverview(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): OverviewData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.OverviewAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            // One offset per load (the visit's own), not per historical
            // instant: re-bucketing history through travel/DST shifts would
            // move rows between visits. Within an hour of the 04:00 boundary
            // a zone change mis-buckets until the next load — unobservable
            // where the 04:00 day start sits far from a DST jump
            // (consolidated_bugs.md E12).
            zone = DayBoundary.zoneOffset(now),
            scheduler = scheduler
        )
        replayInto(scheduler, accumulator::add)
        pageCardsInto(accumulator::addCard)
        return accumulator.toData()
    }

    /**
     * The workload tab, streamed: the log is replayed and the cards are paged
     * into one [StatsAggregator.WorkloadAccumulator]. Its response-time list
     * keeps a roll per question in range — the replay's own working set — but
     * neither the log nor the library is ever listed.
     */
    suspend fun loadWorkload(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): WorkloadData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.WorkloadAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = DayBoundary.zoneOffset(now)
        )
        replayInto(scheduler, accumulator::add)
        pageCardsInto(accumulator::addCard)
        return accumulator.toData()
    }

    /**
     * The memory tab, streamed: the overview accumulator supplies the KPIs from
     * the same replay, and the memory accumulator carries the distributions and
     * the growth curves. Neither the log nor the library is ever listed.
     */
    suspend fun loadMemory(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): MemoryData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val zone = DayBoundary.zoneOffset(now)
        val overview = StatsAggregator.OverviewAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = zone,
            scheduler = scheduler,
            // Only the KPIs are wanted here; the memory tab ranks nothing.
            collectWeakest = false
        )
        val memory = StatsAggregator.MemoryAccumulator(
            scopePaperId = scopePaperId,
            now = now,
            cfg = config,
            zone = zone,
            scheduler = scheduler
        )
        replayInto(scheduler) { review ->
            overview.add(review)
            memory.add(review)
        }
        pageCardsInto { snapshot ->
            overview.addCard(snapshot)
            memory.addCard(snapshot)
        }
        return memory.toData(overview.kpis())
    }

    /**
     * The cards tab, streamed: the cards are paged first (a leech can only be
     * rolled up once its card has been seen), then the log is replayed past the
     * accumulator. The per-paper table is one bucket per paper, not per card.
     */
    suspend fun loadCards(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): CardsData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.CardsAccumulator(
            scopePaperId = scopePaperId,
            now = now,
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        )
        pageCardsInto(accumulator::addCard)
        replayInto(scheduler, accumulator::add)
        return accumulator.toData()
    }

    /**
     * The guessing tab, streamed. Every figure is a count over flagged reviews,
     * so the pass keeps only per-band/per-hour/per-card counters. The chance
     * baseline needs each guessed question's option and key counts, which are
     * read afterwards for those ids only — never for the whole bank.
     */
    suspend fun loadGuessing(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): GuessingData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.GuessingAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = DayBoundary.zoneOffset(now),
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title },
            categoryOfQuestion = scopePaperId?.let { loadCategoryOfQuestion(it) } ?: emptyMap()
        )
        replayInto(scheduler, accumulator::add)
        return accumulator.toData(chanceOfQuestion(accumulator.guessedQuestionIds()))
    }

    /**
     * questionId → chance a uniform random pick would be exactly right:
     * 1 / C(options, correct). Null when the key is missing or inconsistent
     * (a question with no options, or more keys than options), because a
     * baseline invented from bad data is worse than no baseline.
     */
    private suspend fun chanceOfQuestion(ids: Set<String>): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val questionIds = ids.toList()
        val options = db.optionDao().getForQuestionsChunked(questionIds)
            .groupingBy { it.questionId }.eachCount()
        val keys = db.correctAnswerDao().getForQuestionsChunked(questionIds)
            .groupingBy { it.questionId }.eachCount()
        return options.mapNotNull { (questionId, optionCount) ->
            val correctCount = keys[questionId] ?: return@mapNotNull null
            if (optionCount <= 0 || correctCount <= 0 || correctCount > optionCount) {
                return@mapNotNull null
            }
            questionId to 1.0 / binomial(optionCount, correctCount)
        }.toMap()
    }

    /** C(n, k), in doubles: option counts are small but the product can be large. */
    private fun binomial(n: Int, k: Int): Double {
        val kk = minOf(k, n - k)
        var result = 1.0
        for (i in 1..kk) {
            result = result * (n - kk + i) / i
        }
        return result
    }

    /** Replays the log a page at a time into [consume]; it is never listed. */
    private suspend fun replayInto(scheduler: FsrsScheduler, consume: (ReplayReview) -> Unit) {
        val stream = FsrsReplay.Stream(scheduler)
        var afterId = 0L
        while (true) {
            val page = db.reviewLogDao().pageAfterId(afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { consume(stream.accept(it.toRawReview())) }
            afterId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
    }

    /** Pages every card into [consume], in the order the breakdowns tie-break on. */
    private suspend fun pageCardsInto(consume: (CardSnapshot) -> Unit) {
        var afterRowId = 0L
        while (true) {
            val page = db.cardStateDao().pageAfterRowId(afterRowId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { consume(CardSnapshot(it.state.paperId, it.state.toDomain())) }
            afterRowId = page.last().rowid
            if (page.size < PAGE_SIZE) break
        }
    }

    /**
     * Replays the whole log in append (`id`) order, one keyset page at a time.
     * The old `getAll().map { … }` held every row as an entity and then every
     * enriched row, while [FsrsReplay.replay] built two maps keyed by *row* id —
     * a million reviews meant three million-entry structures live at once.
     * [FsrsReplay.Stream] needs only per-card state, so what survives is the
     * enriched rows the aggregator reads plus a single page.
     */
    private suspend fun replayLog(scheduler: FsrsScheduler): List<ReplayReview> {
        val reviews = ArrayList<ReplayReview>()
        replayInto(scheduler) { reviews += it }
        return reviews
    }

    /**
     * Every card, keyset-paged in physical order. `getAll()` materialised all
     * 250k rows as entities before mapping any of them; here one page is live
     * at a time and the order the breakdowns tie-break on is unchanged.
     */
    private suspend fun loadCards(): List<CardSnapshot> {
        val cards = ArrayList<CardSnapshot>()
        pageCardsInto { cards += it }
        return cards
    }

    /**
     * questionId → category title, for the per-category breakdown of one paper.
     * The old `getAllIdCategories()` built a 250k-element list of pairs on the
     * way to this map; paging folds each page in as it arrives, and the paper
     * scope keeps the map to the selected paper's questions.
     */
    private suspend fun loadCategoryOfQuestion(paperId: String): Map<String, String> {
        val titles = db.categoryDao().getAll().associate { it.id to it.title }
        val out = HashMap<String, String>()
        var page = db.questionDao().pageIdCategoriesFirst(paperId, PAGE_SIZE)
        while (true) {
            if (page.isEmpty()) break
            page.forEach { row -> titles[row.categoryId]?.let { out[row.id] = it } }
            if (page.size < PAGE_SIZE) break
            page = db.questionDao().pageIdCategoriesAfter(paperId, page.last().id, PAGE_SIZE)
        }
        return out
    }

    /** One card's logged reviews, oldest first, for the drill-down sheet. */
    suspend fun reviewHistory(questionId: String): List<ReviewHistoryEntry> =
        db.reviewLogDao().historyForQuestion(questionId).map { it.toHistoryEntry() }

    private fun ReviewLogEntity.toRawReview() = FsrsReplay.RawReview(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore.toCardStateKind(),
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter,
        wasGuess = wasGuess,
        wasCorrect = wasCorrect
    )

    private fun ReviewLogEntity.toHistoryEntry() = ReviewHistoryEntry(
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore.toCardStateKind(),
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    /** The stored enum name; an unknown name (old build's value) reads as null. */
    private fun String?.toCardStateKind(): CardStateKind? =
        this?.let { runCatching { CardStateKind.valueOf(it) }.getOrNull() }

    private fun CardStateEntity.toDomain() = CardState(
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

    private companion object {
        /**
         * Rows per keyset page while loading. Bounds each transient read only;
         * the enriched rows the aggregator reads still span the whole log.
         */
        const val PAGE_SIZE = 5_000
    }
}
