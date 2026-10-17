package com.mcqapp.domain

import com.mcqapp.data.local.QuestionRef

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable snapshot of an in-progress test, persisted so a killed app
 * can offer to resume. Selections use lists (sets don't order stably in
 * JSON).
 *
 * The presented order is reproduced from the shuffle/drill seeds rather than
 * from an id list: at 250k questions that list alone was tens of megabytes
 * rewritten on every answer, while the seeds are a few bytes and
 * [PassageBlocks] is deterministic in them. [questionIds] survives only to
 * read snapshots written before the seeds were stored.
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
    val drillRequested: Int = 0
) {
    /** A snapshot resumes only the exact paper + category selection it came from. */
    fun matches(paperId: String, categoryIds: List<String>): Boolean =
        this.paperId == paperId && this.categoryIds.toSet() == categoryIds.toSet()

    fun toJson(): String = SnapshotJson.encodeToString(serializer(), this)

    companion object {
        private val SnapshotJson = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): TestSnapshot? = try {
            SnapshotJson.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            null
        }

        /**
         * Best-effort restore of presented order: saved order for questions
         * that still exist, silently dropping deleted ones. Empty result
         * means the paper changed too much — start fresh instead.
         */
        fun reorder(loaded: List<Question>, savedIds: List<String>): List<Question> {
            if (savedIds.isEmpty()) return loaded
            val byId = loaded.associateBy { it.id }
            val ordered = savedIds.mapNotNull { byId[it] }
            if (ordered.isEmpty()) return emptyList()
            val seen = ordered.map { it.id }.toHashSet()
            return ordered + loaded.filter { it.id !in seen }
        }

        /** The same reorder over the session's lightweight refs. */
        fun reorderRefs(loaded: List<QuestionRef>, savedIds: List<String>): List<QuestionRef> {
            if (savedIds.isEmpty()) return loaded
            val byId = loaded.associateBy { it.id }
            val ordered = savedIds.mapNotNull { byId[it] }
            if (ordered.isEmpty()) return emptyList()
            val seen = ordered.map { it.id }.toHashSet()
            return ordered + loaded.filter { it.id !in seen }
        }

        /**
         * The order a session presented, rebuilt from [base] (the freshly
         * loaded, unshuffled refs). Seed snapshots reproduce the shuffle and
         * drill cut exactly; legacy snapshots replay their saved id list over
         * [presented] (the refs this session loaded), as they always did.
         */
        fun orderRefs(
            base: List<QuestionRef>,
            presented: List<QuestionRef>,
            saved: TestSnapshot
        ): List<QuestionRef> {
            val seed = saved.questionSeed
                ?: return reorderRefs(presented, saved.questionIds)
            val shuffled = PassageBlocks.shuffleAttempt(
                base, seed, saved.questionShuffle
            ) { it.passageId }
            val drillSeed = saved.drillSeed
            return if (drillSeed != null && saved.drillRequested > 0) {
                PassageBlocks.sample(shuffled, saved.drillRequested, drillSeed) { it.passageId }
            } else {
                shuffled
            }
        }
    }
}
