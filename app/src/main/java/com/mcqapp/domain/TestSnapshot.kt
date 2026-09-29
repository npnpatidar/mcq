package com.mcqapp.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable snapshot of an in-progress test, persisted so a killed app
 * can offer to resume. Selections use lists (sets don't order stably in
 * JSON); question ids capture the presented order (post-shuffle).
 */
@Serializable
data class TestSnapshot(
    val paperId: String,
    val categoryIds: List<String>,
    val questionIds: List<String>,
    val selections: Map<String, List<String>> = emptyMap(),
    val revealed: Set<String> = emptySet(),
    val flagged: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0
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
    }
}
