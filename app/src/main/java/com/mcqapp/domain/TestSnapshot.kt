package com.mcqapp.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable snapshot of an in-progress test, persisted so a killed app
 * can offer to resume: what the session *is* — its scope, the seeds that
 * reproduce the presented order, where the reader was, how much time is left.
 *
 * The answers are not in here. Each question's selection, reveal, flag and dwell
 * are a `test_progress` row, so writing one answer is one small upsert; carrying
 * them in this document meant re-serialising every answer so far on every tap.
 * The inline fields below remain the *format*: a snapshot written before the
 * rows existed (or restored from a backup) still carries its answers here, and
 * [answers] adopts them into rows when it resumes.
 *
 * Selections use lists (sets don't order stably in JSON). The presented order is
 * reproduced from the shuffle/drill seeds rather than from an id list: at 250k
 * questions that list alone was tens of megabytes rewritten on every answer,
 * while the seeds are a few bytes and [SessionOrder] is deterministic in them.
 * [questionIds] survives only to read snapshots written before the seeds were
 * stored.
 */
@Serializable
data class TestSnapshot(
    val paperId: String,
    val categoryIds: List<String>,
    val questionIds: List<String> = emptyList(),
    val selections: Map<String, List<String>> = emptyMap(),
    val revealed: Set<String> = emptySet(),
    val flagged: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val dwellSeconds: Map<String, Long> = emptyMap(),
    /** Seed for the block-aware question shuffle; null on legacy snapshots. */
    val questionSeed: Long? = null,
    val questionShuffle: Boolean = false,
    /** Seed for the option shuffle, or 0 when options are not shuffled. */
    val optionShuffleSeed: Long = 0L,
    val drillSeed: Long? = null,
    /** Requested drill size (0 = whole selection); the block cut may overshoot. */
    val drillRequested: Int = 0,
    /** The session shape: without it a drill/mistakes round and a whole-paper
     *  test of the same scope were interchangeable on resume, collapsing or
     *  inflating the session (consolidated_bugs.md D1/D4). */
    val isDrill: Boolean = false,
    val mistakesOnly: Boolean = false,
    /**
     * Canonical expanded scope identity (section 12.2). Empty on legacy
     * snapshots, which fall back to the raw tick-set comparison.
     */
    val scopeKey: String = "",
    /** The selector that produced the session; null on legacy snapshots. */
    val testType: String? = null,
    /** The requested length; 0 on legacy snapshots. */
    val requestedCount: Int = 0,
    /** Frozen metric-based selection, in presented order. */
    val selectedQuestionIds: List<String> = emptyList(),
    /** Compatibility; new snapshots write 2, legacy snapshots read as 1. */
    val samplingVersion: Int = 1,
    /** Resume only the same exclusion rule. */
    val exclusionMode: String = "NONE",
    /** Recorded practice targets, sorted and deduplicated. */
    val targetTopicIds: List<String> = emptyList(),
    /** Seconds already spent in an untimed session, banked on every autosave
     *  so a kill does not shrink the recorded duration (D7). */
    val elapsedSeconds: Int = 0,
    /** Wall-clock moment this document was written, so a snapshot whose
     *  post-submit clear failed is recognised as finished (D8). */
    val savedAt: Long = 0
) {
    /**
     * A snapshot resumes only the exact paper + category selection — and the
     * exact session shape — it came from. A drill and a mistakes-only round
     * are different sessions of the same scope; offering one to the other
     * adopted the new session's shape with the saved order, collapsing a
     * 500-question test to a 10-question drill or silently dropping the
     * drill's cut.
     */
    fun matches(
        paperId: String,
        categoryIds: List<String>,
        isDrill: Boolean = false,
        mistakesOnly: Boolean = false,
        drillRequested: Int = 0,
        testType: String? = null,
        requestedCount: Int = 0,
        exclusionMode: String = "NONE",
        targetTopicIds: List<String> = emptyList(),
        scopeKey: String = ""
    ): Boolean {
        // New snapshots carry the canonical expanded scope; a legacy snapshot
        // (and a legacy caller) has no key, so it keeps the raw tick-set
        // comparison it was written with. The two forms never match each
        // other: a legacy snapshot must not be offered to a new test. A
        // type-tagged document below sampling version 2 is malformed (only a
        // legacy writer leaves it at 1), so it is not current either.
        val scopeMatches = if (this.scopeKey.isNotEmpty() || scopeKey.isNotEmpty()) {
            this.scopeKey.isNotEmpty() && this.scopeKey == scopeKey && this.samplingVersion >= 2
        } else {
            this.categoryIds.toSet() == categoryIds.toSet()
        }
        return this.paperId == paperId && scopeMatches &&
            this.isDrill == isDrill && this.mistakesOnly == mistakesOnly &&
            (!isDrill || this.drillRequested == drillRequested) &&
            this.testType == testType && this.requestedCount == requestedCount &&
            this.exclusionMode == exclusionMode &&
            this.targetTopicIds.toSet() == targetTopicIds.toSet()
    }

    /** Whether any question was actually answered (reaching one is not answering). */
    val hasAnswers: Boolean get() = selections.any { it.value.isNotEmpty() }

    /**
     * A snapshot written before the bounded-Test metadata existed: no type, or
     * a sampling version below 2 (section 7.6). It can only resume as a
     * labelled Short/Legacy session, never as a bounded Random test.
     */
    val isLegacy: Boolean get() = testType == null || samplingVersion < TestPlan.SAMPLING_VERSION

    /** This header with the per-question payload stripped out. */
    fun header(): TestSnapshot = copy(
        selections = emptyMap(),
        revealed = emptySet(),
        flagged = emptySet(),
        dwellSeconds = emptyMap()
    )

    /**
     * The snapshot a resume should offer: this header plus the answers held as
     * rows. The rows win whenever there are any — an adopting resume rewrites
     * this document header-only — and with none the inline answers are left
     * alone, which is what a pre-rows snapshot (or a restored backup) carries.
     */
    fun withAnswers(answers: List<TestAnswer>): TestSnapshot =
        if (answers.isEmpty()) this else copy(
            selections = answers.filter { it.selection.isNotEmpty() }
                .associate { it.questionId to it.selection.toList() },
            revealed = answers.filter { it.revealed }.mapTo(LinkedHashSet()) { it.questionId },
            flagged = answers.filter { it.flagged }.mapTo(LinkedHashSet()) { it.questionId },
            dwellSeconds = answers.filter { it.dwellSeconds > 0L }
                .associate { it.questionId to it.dwellSeconds }
        )

    /**
     * The per-question state this snapshot holds inline, one entry per question
     * it says anything about — every field, so `withAnswers(answers())` returns
     * an equal snapshot. Resuming a legacy snapshot writes these as rows before
     * the next answer, so the payload is never dropped.
     */
    fun answers(): List<TestAnswer> {
        val ids = LinkedHashSet<String>()
        ids += selections.keys
        ids += revealed
        ids += flagged
        ids += dwellSeconds.keys
        return ids.map { id ->
            TestAnswer(
                questionId = id,
                selection = selections[id]?.toSet() ?: emptySet(),
                revealed = id in revealed,
                flagged = id in flagged,
                dwellSeconds = dwellSeconds[id] ?: 0L
            )
        }
    }

    fun toJson(): String = SnapshotJson.encodeToString(serializer(), this)

    companion object {
        private val SnapshotJson = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): TestSnapshot? = try {
            SnapshotJson.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            null
        }

        /**
         * The order a session presented, rebuilt from [base] (the freshly
         * loaded order, unshuffled). Seed snapshots reproduce the shuffle and
         * drill cut exactly; legacy snapshots replay their saved id list, as
         * they always did. Null means the paper changed too much — the caller
         * starts fresh instead of resuming into a session missing questions.
         */
        fun orderOf(base: SessionOrder, saved: TestSnapshot): SessionOrder? {
            // A bounded Test freezes its sample: replay exactly those ids and
            // never re-rank. The seed path below cannot reproduce a
            // metric-based or bounded sample, so it must not run for them.
            if (saved.selectedQuestionIds.isNotEmpty()) {
                return base.subsetInOrder(saved.selectedQuestionIds)
            }
            val seed = saved.questionSeed ?: return base.reorderedTo(saved.questionIds)
            val shuffled = base.shuffled(seed, saved.questionShuffle)
            val drillSeed = saved.drillSeed
            return if (drillSeed != null && saved.drillRequested > 0) {
                shuffled.sampled(saved.drillRequested, drillSeed)
            } else {
                shuffled
            }
        }
    }
}

/**
 * One question reached in an in-progress session: exactly the per-question part
 * of [TestSnapshot], stored as one `test_progress` row so writing an answer
 * never touches the answers before it.
 */
data class TestAnswer(
    val questionId: String,
    val selection: Set<String> = emptySet(),
    val revealed: Boolean = false,
    val flagged: Boolean = false,
    val dwellSeconds: Long = 0L
)
