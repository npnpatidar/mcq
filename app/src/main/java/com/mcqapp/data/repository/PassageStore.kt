package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Passage CRUD: create, read, update, delete (guarded), reorder and
 * assign/unassign members. Split out of [McqRepository] like the other
 * collaborators so the facade stays a table of contents.
 *
 * Deleting a passage that still has members is refused (D5 in
 * PASSAGE-QUESTIONS.md): the caller unassigns first, so no question ever
 * loses its context by surprise. This mirrors `deleteCategory`, which
 * reparents children rather than orphaning them.
 */
internal class PassageStore(
    private val db: AppDatabase,
    private val mapper: QuestionContentMapper
) {

    fun observePassages(): Flow<List<Passage>> =
        db.passageDao().observeAll().map { list -> list.map { mapper.toPassage(it) } }

    suspend fun getPassage(passageId: String): Passage? =
        db.passageDao().getById(passageId)?.let { mapper.toPassage(it) }

    suspend fun getPassagesByIds(ids: List<String>): List<Passage> {
        if (ids.isEmpty()) return emptyList()
        return db.passageDao().getByIds(ids).map { mapper.toPassage(it) }
    }

    suspend fun getPassagesForPaper(paperId: String): List<Passage> {
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        if (categoryIds.isEmpty()) return emptyList()
        val byCategory = categoryIds.flatMap { db.passageDao().getByCategory(it) }
        return byCategory.map { mapper.toPassage(it) }
    }

    /**
     * Creates or updates a passage. Returns the passage id; a blank incoming
     * id mints a fresh one. Member questions are never touched here —
     * assigning members is explicit (see [assignQuestions], [unassignAll]).
     */
    suspend fun savePassage(passage: Passage): String {
        val id = passage.id.ifBlank {
            "passage-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        }
        Logger.d("REPO", "savePassage(id=$id, category=${passage.categoryId}, title='${passage.title}')")
        db.withTransaction {
            val existing = db.passageDao().getById(id)
            db.passageDao().upsert(
                PassageEntity(
                    id = id,
                    categoryId = passage.categoryId,
                    title = passage.title,
                    text = mapper.toEntity(passage.copy(id = id)).text,
                    image = passage.image,
                    sortOrder = existing?.sortOrder ?: passage.sortOrder
                )
            )
        }
        return id
    }

    /** How many questions still point at the passage — the delete guard's number. */
    suspend fun memberCount(passageId: String): Int = db.passageDao().memberCount(passageId)

    /**
     * Deletes a passage, refusing while members remain. The caller shows the
     * member count so the user can unassign first.
     */
    suspend fun deletePassage(passageId: String): Boolean {
        val members = db.passageDao().memberCount(passageId)
        if (members > 0) {
            Logger.w("REPO", "deletePassage($passageId) refused: $members members remain")
            return false
        }
        db.passageDao().deleteById(passageId)
        Logger.i("REPO", "deletePassage($passageId) deleted")
        return true
    }

    /** Assigns questions to a passage; they keep their own category. */
    suspend fun assignQuestions(questionIds: Collection<String>, passageId: String) {
        if (questionIds.isEmpty()) return
        db.withTransaction {
            questionIds.forEach { db.passageDao().assignToMember(it, passageId) }
        }
        Logger.i("REPO", "assignQuestions(${questionIds.size} ids -> $passageId)")
    }

    /** Clears passage membership, making the questions standalone again. */
    suspend fun unassignQuestions(questionIds: Collection<String>) {
        if (questionIds.isEmpty()) return
        db.withTransaction {
            questionIds.forEach { db.passageDao().assignToMember(it, null) }
        }
        Logger.i("REPO", "unassignQuestions(${questionIds.size} ids)")
    }

    /** Detaches every member of a passage (used before a guarded delete). */
    suspend fun unassignAll(passageId: String) {
        val members = db.passageDao().memberIds(passageId)
        unassignQuestions(members)
    }

    /** Moves a passage up/down among same-category siblings. */
    suspend fun movePassage(passageId: String, delta: Int): Boolean {
        return db.withTransaction {
            val passage = db.passageDao().getById(passageId) ?: return@withTransaction false
            val siblings = db.passageDao().getByCategory(passage.categoryId)
            val fromIndex = siblings.indexOfFirst { it.id == passageId }
            if (fromIndex < 0) return@withTransaction false
            val target = (fromIndex + delta).coerceIn(siblings.indices)
            if (target == fromIndex) return@withTransaction false
            val order = com.mcqapp.domain.Reorder.normalizedOrder(
                siblings.map { it.id }, fromIndex, delta
            )
            order.forEach { (id, sortOrder) -> db.passageDao().updateSortOrder(id, sortOrder) }
            Logger.i("REPO", "movePassage($passageId by $delta)")
            true
        }
    }

    /**
     * Passages of one category with the questions that belong to each, in
     * authored order — the shape Browse and the test-session block builder
     * both need.
     */
    suspend fun buildBlocks(questions: List<Question>): List<PassageBlock> {
        val passageIds = questions.mapNotNull { it.passageId }.distinct()
        if (passageIds.isEmpty()) return emptyList()
        val passages = getPassagesByIds(passageIds).associateBy { it.id }
        return questions
            .filter { it.passageId != null && it.passageId in passages }
            .groupBy { it.passageId!! }
            .map { (passageId, members) -> PassageBlock(passages.getValue(passageId), members) }
            .sortedBy { it.passage.sortOrder }
    }
}

/** A passage together with the questions that share it, in session order. */
data class PassageBlock(
    val passage: Passage,
    val questions: List<Question>
)
