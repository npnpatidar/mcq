package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.local.getCategoryTitlesByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.tagsForQuestionsChunked
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.Calibration
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.CardsData
import com.mcqapp.domain.stats.CoveragePaper
import com.mcqapp.domain.stats.CoverageStatsBuilder
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.GuessingData
import com.mcqapp.domain.stats.MemoryData
import com.mcqapp.domain.stats.OverviewData
import com.mcqapp.domain.stats.ProgressData
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.Reliability
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.ReviewHistoryEntry
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.domain.stats.WorkloadData
import com.mcqapp.util.Logger
import kotlinx.coroutines.CancellationException

/**
 * Loads what the statistics page needs: the replayed review log, every card's
 * current schedule, and the paper titles the breakdowns group by. The log and
 * the cards are separate reads — the retention tab streams past the log and
 * never builds it, so [loadLibrary] and [loadReviews] are asked for
 * independently. The list-shaped reads are kept whole so the ViewModel can
 * re-slice ranges and scopes in memory without touching the database.
 */
internal class StatsStore(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val gradingRules: GradingRulesStore
) {

    /**
     * The scheduler config every read in this store measures against, with
     * the grading version guaranteed to exist first.
     *
     * [gradingRules] is not an alternative source of the cuts; it is the
     * seed. A stats read can be the first thing a v27 install does (Statistics
     * opened before any study session or Settings save), and without this call
     * `gradingVersion` is 0 -> [loadCalibration]'s `epochStart` falls back to
     * 0 -> pre-v27 rows graded under the retired reading allowance are pooled
     * with rows graded under absolute 6/60. Once the `prior` row exists this
     * is a no-op read, so the read path writes only on the first pass.
     *
     * Invariant: a review's `gradingVersion` must name the row whose cuts
     * graded it. `writeCuts` puts the pair and its id in one DataStore edit,
     * so the config is trusted only when the id it was read with is positive.
     * A 0 means a Reset has removed the id but its replacement has not landed,
     * and the id from the seed above must NOT be substituted: that would pair
     * a version with cuts it did not grade. The read is retried instead, which
     * lets `currentVersion()` seed the pair and id atomically.
     *
     * If seeding fails, a positive stored id is still usable — the crash
     * repair will fill in the row on the next write path — and the report is
     * built against it. The raw `ladder_version` is deliberately not used as a
     * fallback: it ignores the rule gate and could name a pair retired on
     * upgrade. With no usable id the method returns null: callers must fail
     * loudly and let `StatsViewModel.loadError` render the tab, rather than
     * building a version-0 report that pools the pre-v27 history this feature
     * exists to exclude. That is the one failure shape worse than an error
     * screen.
     */
    private suspend fun currentConfig(): SchedulerConfig? {
        repeat(CONFIG_READ_ATTEMPTS) {
            try {
                gradingRules.currentVersion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("STATS", "Could not seed the grading version", e)
                return settings.schedulerConfigNow().takeIf { it.gradingVersion > 0 }
            }
            val config = settings.schedulerConfigNow()
            if (config.gradingVersion > 0) return config
        }
        return null
    }

    /**
     * The config a tab needs, or a failure StatsViewModel already knows how to
     * show. A contaminated report is never an acceptable fallback.
     */
    private suspend fun requireConfig(): SchedulerConfig =
        currentConfig() ?: throw GradingRuleUnavailable()

    /** The calibration report plus the answer-time pair that may stand alone. */
    private data class CalibrationLoad(
        val report: Calibration.Report,
        val dwellSuggestion: Calibration.CutSuggestion?,
        /** The config the report was actually built against (see [configWithEpoch]). */
        val config: SchedulerConfig
    )

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
        val config = requireConfig()
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
        val config = requireConfig()
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
        val config = requireConfig()
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
        // Plan §6.2/§6.3: the tag and all-papers category slices look up only
        // the reviewed ids, after the pass, so they stay bounded by the log's
        // working set rather than the whole bank.
        val reviewedIds = accumulator.reviewedQuestionIds().toList()
        val tagsOfQuestion = if (reviewedIds.isEmpty()) {
            emptyMap()
        } else {
            db.questionTagDao().tagsForQuestionsChunked(reviewedIds)
        }
        val categoriesForSlice = if (scopePaperId != null || reviewedIds.isEmpty()) {
            // The scoped map above already labels the per-question groups.
            emptyMap()
        } else {
            db.questionDao().getCategoryTitlesByIdsChunked(reviewedIds)
        }
        val data = accumulator.toData(tagsOfQuestion, categoriesForSlice)
        val calibration = loadCalibration(scopePaperId, now, config)
        // The calibration load can resolve a newer config when the read raced
        // a Reset; every field below must describe the report it returned.
        val effective = calibration.config
        return data.copy(
            calibrationReport = calibration.report,
            timeBasedGrading = !effective.correctnessOnlyGrading,
            // The two suggestions answer different questions and never appear
            // together: the contrast needs labels, the answer-time pair needs
            // only dwells, and only one rule can be in force at a time.
            cutSuggestion = if (effective.correctnessOnlyGrading) {
                null
            } else {
                Calibration.suggestCuts(
                    report = calibration.report,
                    fastSeconds = effective.fastSeconds,
                    slowSeconds = effective.slowSeconds
                )
            },
            dwellSuggestion = calibration.dwellSuggestion
        )
    }

    /**
     * Grade-level calibration (plan phase 2). A second, keyset-paged pass over
     * the log that keeps only the rows the pairing can use. The result is
     * cached on the exact inputs a refresh would change; an open block cannot
     * serve a stale answer forever because `max(reviewedAt)` is in the key.
     *
     * The stored `predictedRecall` is reused, never re-derived: a refit would
     * otherwise silently rewrite history's forecasts.
     */
    private suspend fun loadCalibration(
        scopePaperId: String?,
        now: Long,
        config: SchedulerConfig
    ): CalibrationLoad {
        // [configWithEpoch] can resolve a newer config when the read raced a
        // Reset; the report, its cache key and the suggestion all use it.
        val (resolvedConfig, versionStart) = configWithEpoch(config)
        val version = resolvedConfig.gradingVersion
        var maxReviewedAt = 0L
        // The weight-set boundary is tracked unscoped: a refit anywhere in the
        // log changes the forecast yardstick for every paper's report.
        var fitStartedAt = Long.MAX_VALUE
        var staleFitSeen = false
        // Which versions ignored the clock: answer-time evidence only trusts
        // dwells measured while the clock made no colour claim.
        val correctnessOnlyVersions = db.gradingRulesDao().allVersions()
            .filter { it.correctnessOnly }
            .map { it.id.toInt() }
            .toSet()
        val rows = ArrayList<Calibration.Row>()
        var afterId = 0L
        while (true) {
            val page = db.reviewLogDao().pageAfterId(afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { row ->
                if (row.reviewedAt > maxReviewedAt) maxReviewedAt = row.reviewedAt
                if (row.reviewedAt >= versionStart) {
                    val fit = row.fsrsFitVersion
                    if (fit != null && fit != resolvedConfig.fsrsFitVersion) staleFitSeen = true
                    if (fit == resolvedConfig.fsrsFitVersion && row.reviewedAt < fitStartedAt) {
                        fitStartedAt = row.reviewedAt
                    }
                }
                if (scopePaperId != null && row.paperId != scopePaperId) return@forEach
                rows += row.toCalibrationRow(correctnessOnlyVersions)
            }
            afterId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        // Plan §7: a refit ends the epoch. The reviews before it were forecast
        // with another weight set, so the epoch restarts at the first label
        // recorded under the current weights.
        val refitInEpoch = staleFitSeen && fitStartedAt == Long.MAX_VALUE
        val epochStart = if (staleFitSeen && fitStartedAt != Long.MAX_VALUE) {
            maxOf(versionStart, fitStartedAt)
        } else {
            versionStart
        }
        val closedBlockEnd = if (epochStart > 0L && now > epochStart) {
            epochStart + ((now - epochStart) / BLOCK_MS) * BLOCK_MS
        } else {
            0L
        }
        val key = CalibrationCacheKey(
            scopePaperId = scopePaperId,
            gradingVersion = version,
            fsrsFitVersion = resolvedConfig.fsrsFitVersion,
            blockEnd = closedBlockEnd,
            maxReviewedAt = maxReviewedAt
        )
        // Answer times are cheap evidence and need no cache: the rows are
        // already built by the pass above.
        val dwellSuggestion = if (resolvedConfig.correctnessOnlyGrading) {
            Calibration.suggestCutsFromDwell(
                reviews = rows,
                now = now,
                fastSeconds = resolvedConfig.fastSeconds,
                slowSeconds = resolvedConfig.slowSeconds
            )
        } else {
            null
        }
        calibrationCache[key]?.let {
            return CalibrationLoad(it, dwellSuggestion, resolvedConfig)
        }
        val report = Calibration.report(rows, now, epochStart, refitInEpoch)
        calibrationCache.clear()
        calibrationCache[key] = report
        return CalibrationLoad(report, dwellSuggestion, resolvedConfig)
    }

    /**
     * [config] paired with the `effectiveFrom` of the row its `gradingVersion`
     * names.
     *
     * A version whose row is missing cannot be used: `epochStart = 0` pools
     * every review recorded before the rule existed, which is the
     * contamination this feature exists to exclude. The row is normally there
     * — [requireConfig] seeds it — and one retry covers the write order of a
     * save or Reset: the cuts and their new id land in one DataStore edit and
     * the `grading_versions` row is inserted after it, so a stats read can
     * observe the id for an instant before its row exists. The retry calls
     * [currentConfig], whose `currentVersion()` re-inserts a missing row, so it
     * heals the window instead of just waiting it out. Writing the row inside
     * the Reset's edit is not possible — the row lives in Room and the id in
     * DataStore — so this is the only fix that covers every writer.
     */
    private suspend fun configWithEpoch(
        config: SchedulerConfig
    ): Pair<SchedulerConfig, Long> {
        if (config.gradingVersion > 0) {
            db.gradingRulesDao().version(config.gradingVersion.toLong())
                ?.let { return config to it.effectiveFrom }
        }
        val retried = currentConfig() ?: throw GradingRuleUnavailable()
        val row = if (retried.gradingVersion > 0) {
            db.gradingRulesDao().version(retried.gradingVersion.toLong())
        } else {
            null
        }
        return retried to (row?.effectiveFrom ?: throw GradingRuleUnavailable())
    }

    private fun ReviewLogEntity.toCalibrationRow(
        correctnessOnlyVersions: Set<Int>
    ) = Calibration.Row(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        gradeSource = gradeSource,
        proposedRating = proposedRating,
        gradingVersion = gradingVersion,
        fsrsFitVersion = fsrsFitVersion,
        predictedRecall = predictedRecall,
        stateBefore = stateBefore.toCardStateKind(),
        wasGuess = wasGuess,
        wasCorrect = wasCorrect,
        wasSkipped = wasSkipped,
        intervalDaysAfter = intervalDaysAfter,
        elapsedSeconds = elapsedSeconds,
        correctnessOnly = gradingVersion in correctnessOnlyVersions
    )

    private data class CalibrationCacheKey(
        val scopePaperId: String?,
        val gradingVersion: Int,
        val fsrsFitVersion: Int,
        val blockEnd: Long,
        val maxReviewedAt: Long
    )

    /**
     * One entry: the report for the exact inputs a refresh would change. The
     * Stats tab loads on `Dispatchers.Default` and the session-end controller
     * on `Dispatchers.IO`, so the map must tolerate two threads.
     */
    private val calibrationCache =
        java.util.concurrent.ConcurrentHashMap<CalibrationCacheKey, Calibration.Report>()

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
        val config = requireConfig()
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
        val config = requireConfig()
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
        val config = requireConfig()
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
            scheduler = scheduler,
            // The per-paper means only exist unscoped; skip the read otherwise.
            paperTitleOf = if (scopePaperId == null) {
                db.paperDao().getAll().associate { it.id to it.title }
            } else {
                emptyMap()
            }
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
        val config = requireConfig()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.CardsAccumulator(
            scopePaperId = scopePaperId,
            now = now,
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        )
        pageCardsInto(accumulator::addCard)
        replayInto(scheduler, accumulator::add)
        val coverage = CoverageStatsBuilder.build(
            rows = db.questionDao().getCoverageByPaper(scopePaperId).map {
                CoveragePaper(
                    paperId = it.paperId,
                    label = it.label,
                    questions = it.questions,
                    studied = it.studied,
                    bookmarked = it.bookmarked
                )
            },
            scoped = scopePaperId != null
        )
        return accumulator.toData(coverage)
    }

    /**
     * The progress tab, streamed: one pass of the log into counters plus the
     * bounded seen-card set and the per-day buckets. Neither the log nor the
     * library is ever listed.
     */
    suspend fun loadProgress(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long
    ): ProgressData {
        val config = requireConfig()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.ProgressAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = DayBoundary.zoneOffset(now)
        )
        replayInto(scheduler, accumulator::add)
        return accumulator.toData()
    }

    /**
     * The sessions tab and the sidebar history, streamed. With
     * [collectEntries] the sitting list is kept and the topic titles are read
     * for the reviewed ids only, after the pass; the statistics tab leaves both
     * off and pays only for the chart counters.
     */
    suspend fun loadSessions(
        scopePaperId: String?,
        range: StatsAggregator.Range,
        now: Long,
        collectEntries: Boolean
    ): SessionsData {
        val config = requireConfig()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.SessionsAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = DayBoundary.zoneOffset(now),
            collectEntries = collectEntries,
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        )
        replayInto(scheduler, accumulator::add)
        val categoryOfQuestion = if (!collectEntries) {
            emptyMap()
        } else {
            val reviewedIds = accumulator.reviewedQuestionIds().toList()
            if (reviewedIds.isEmpty()) {
                emptyMap()
            } else {
                db.questionDao().getCategoryTitlesByIdsChunked(reviewedIds)
            }
        }
        return accumulator.toData(categoryOfQuestion)
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
        val config = requireConfig()
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
            val chance = Reliability.chanceOf(optionCount, correctCount) ?: return@mapNotNull null
            questionId to chance
        }.toMap()
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

        /** One evidence block's width, for the cache's closed-block boundary. */
        const val BLOCK_MS = Calibration.BLOCK_DAYS * 86_400_000L

        /**
         * How many times a config read retries when it observes
         * `gradingVersion == 0`. That state only exists between a Reset's id
         * removal and its replacement row, both of which the retrying read
         * itself writes, so three attempts is already generous.
         */
        const val CONFIG_READ_ATTEMPTS = 3
    }
}

/**
 * The grading rule could not be resolved, and a report without one would pool
 * history from before the rule existed.
 *
 * `StatsViewModel` puts `e.message` into `loadError` and the Statistics screen
 * renders it in place of the tabs, so this message reaches the learner: keep it
 * a complete sentence, not a log label.
 */
internal class GradingRuleUnavailable :
    IllegalStateException("The grading rule could not be loaded.")
