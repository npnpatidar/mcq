package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.COPY_ID_PROBE
import com.mcqapp.data.local.countForQuestionsChunked
import com.mcqapp.data.local.likePrefixPattern
import com.mcqapp.data.local.removeAllChunked
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Papers and categories: the library tree, paper lifecycle (save, duplicate,
 * delete, delete impact) and category management. Split out of
 * [McqRepository]; question deep-copies go through [QuestionStore], and
 * deleting a paper drops the resume snapshot through [SettingsStore].
 */
internal class PaperStore(
    private val db: AppDatabase,
    private val questions: QuestionStore,
    private val settings: SettingsStore
) {

    fun observePapers(): Flow<List<Paper>> =
        combine(
            db.paperDao().observeAll(),
            db.categoryDao().observeAll(),
            db.questionDao().observeCategoryCounts()
        ) { papers, categories, counts ->
            val countMap = counts.associate { it.categoryId to it.cnt }
            papers.map { it.toDomain(countMap) }
        }

    suspend fun getPaper(paperId: String): Paper? {
        Logger.d("REPO", "getPaper($paperId)")
        return db.paperDao().getById(paperId)?.toDomain()
    }

    /**
     * `countMap` lets the library pass the counts it already has; null means
     * fetch them here. The previous default of an empty map produced a Paper
     * whose every category reported zero questions — a silent trap for any
     * caller that asked `totalQuestions`, which is exactly what the drill
     * dialog and the Study screen do.
     */
    private suspend fun PaperEntity.toDomain(countMap: Map<String, Int>? = null): Paper {
        val categories = db.categoryDao().getByPaper(id)
        val counts = countMap
            ?: db.questionDao().getCategoryCounts().associate { it.categoryId to it.cnt }
        return Paper(
            id = id,
            title = title,
            description = description,
            durationMinutes = durationMinutes,
            negativeMarking = negativeMarking,
            categories = buildTree(categories, counts)
        )
    }

    private fun buildTree(
        categories: List<CategoryEntity>,
        countMap: Map<String, Int>
    ): List<CategoryNode> {
        val byParent = categories.groupBy { it.parentId }
        fun build(parentId: String?, counts: Map<String, Int>): List<CategoryNode> =
            (byParent[parentId] ?: emptyList()).map { category ->
                CategoryNode(
                    id = category.id,
                    paperId = category.paperId,
                    title = category.title,
                    parentId = category.parentId,
                    children = build(category.id, counts),
                    questionCount = counts[category.id] ?: 0
                )
            }
        return build(null, countMap)
    }

    suspend fun savePaper(paper: Paper) {
        Logger.d("REPO", "savePaper(${paper.id}, '${paper.title}')")
        db.paperDao().upsert(
            PaperEntity(
                id = paper.id,
                title = paper.title,
                description = paper.description,
                durationMinutes = paper.durationMinutes,
                negativeMarking = paper.negativeMarking
            )
        )
    }

    /**
     * What deleting [paperId] would take with it, so the confirmation can say so
     * rather than just naming the paper. Deletion is thorough by design — it
     * also drops history, bookmarks, schedules and any resume snapshot — which
     * makes a single unconfirmed tap expensive.
     */
    suspend fun deleteImpact(paperId: String): McqRepository.DeleteImpact {
        val questionIds = db.questionDao().getIdsByPaper(paperId)
        return McqRepository.DeleteImpact(
            questions = questionIds.size,
            attempts = db.attemptDao().countByPaper(paperId),
            bookmarks = if (questionIds.isEmpty()) 0
            else db.bookmarkDao().countForQuestionsChunked(questionIds),
            schedules = db.cardStateDao().countByPaper(paperId)
        )
    }

    suspend fun deletePaper(paperId: String) {
        Logger.d("REPO", "deletePaper($paperId)")
        // One transaction: bookmark cleanup, history, schedules and the paper
        // delete (which cascades categories/questions/options/correct answers)
        // must land together, or a crash midway leaves the database claiming a
        // test was taken against questions that no longer exist.
        db.withTransaction {
            // Collect first: deleting the paper cascades its questions away.
            val questionIds = db.questionDao().getIdsByPaper(paperId)
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAllChunked(questionIds)
            // History and SM-2 cards have no path to the paper except this
            // paperId column, so a plain cascade leaves them behind as orphans:
            // attempts would still show up in History under a title that no
            // longer exists, and question_results still reference dead questions.
            db.attemptDao().deleteByPaper(paperId)
            db.cardStateDao().deleteByPaper(paperId)
            db.paperDao().deleteById(paperId)
        }
        // A snapshot is one global slot, so it has to be dropped by hand — and
        // only when it belongs to the paper that just went: resuming a test
        // whose questions are gone would present an empty paper.
        settings.discardProgressFor(paperId)
    }

    suspend fun addCategory(paperId: String, title: String, parentId: String?): String {
        val id = "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        Logger.d("REPO", "addCategory($paperId, '$title', parent=$parentId) -> $id")
        // Category sortOrder is one global sequence per paper, so a fresh
        // category takes the next slot after the current maximum — 0 would
        // collide with the first category and leave the order to the title
        // tiebreak.
        val next = (db.categoryDao().getByPaper(paperId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        db.categoryDao().upsert(
            CategoryEntity(
                id = id,
                paperId = paperId,
                title = title,
                parentId = parentId,
                sortOrder = next
            )
        )
        return id
    }

    suspend fun deleteCategory(categoryId: String) {
        // One transaction: bookmark cleanup, the reparent and the category
        // delete (which cascades its own questions) must land together.
        db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction
            // `categories` has no self-referencing FK on parentId, so a plain
            // delete leaves every descendant pointing at a row that no longer
            // exists: still returned by getByPaper and counted in the UI, but
            // unreachable from the tree. The user asked to delete this
            // category, not its subtree, so promote the children one level.
            db.categoryDao().reparentChildren(
                fromParentId = categoryId,
                toParentId = category.parentId
            )
            val questionIds = db.questionDao().getIdsByCategory(categoryId)
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAllChunked(questionIds)
            db.categoryDao().deleteById(categoryId)
        }
    }

    /** Moves a category up/down among same-paper, same-parent siblings. */
    suspend fun moveCategory(categoryId: String, delta: Int): Boolean {
        // One transaction: the reorder rewrites the paper's whole category
        // sequence, so a crash must not leave two of them sharing a slot.
        return db.withTransaction {
            val plan = planSiblingMove(categoryId, delta)
                ?: return@withTransaction false
            renumberPaperPreorder(plan.paperId) { parent, children ->
                if (parent == plan.movedParentId) plan.children else children
            }
            Logger.i("REPO", "moveCategory($categoryId by $delta)")
            true
        }
    }

    /** Internal fact of a planned move: what changed and where. */
    private class TreePlan(
        val paperId: String,
        val movedParentId: String?,
        val children: List<com.mcqapp.data.local.CategoryEntity>
    )

    /**
     * Computes the moved category's parent's new children order, shared by
     * every tree-shaping operation. Null when the move is a no-op, unknown
     * category, or out of bounds.
     */
    private suspend fun planSiblingMove(categoryId: String, delta: Int): TreePlan? {
        val category = db.categoryDao().getById(categoryId) ?: return null
        val siblings = childrenOf(category.paperId, category.parentId)
        val fromIndex = siblings.indexOfFirst { it.id == categoryId }
        if (fromIndex < 0) return null
        val target = (fromIndex + delta).coerceIn(siblings.indices)
        if (target == fromIndex) return null
        val children = siblings.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(target, item)
        }
        return TreePlan(category.paperId, category.parentId, children)
    }

    private suspend fun childrenOf(
        paperId: String,
        parent: String?
    ): List<com.mcqapp.data.local.CategoryEntity> =
        db.categoryDao().getByPaper(paperId)
            .filter { it.parentId == parent }
            .sortedBy { it.sortOrder }

    /**
     * Rewrites the paper's category sortOrder as one dense pre-order
     * traversal of the tree, with [override] substituting one parent's
     * children (the shape a move changed).
     *
     * Category sortOrder is ONE global sequence per paper — parents
     * immediately followed by their children — so question order (which
     * sorts by it) always matches the drawer's tree. Renumbering only the
     * touched siblings (indices 0..n of their level) used to collide with
     * the untouched children's slots: moving Mathematics up in the demo
     * paper left Chemistry and Physics sharing sortOrders with the roots,
     * and the (sortOrder, title) tiebreak surfaced as
     * "maths, chemistry, science, physics".
     */
    private suspend fun renumberPaperPreorder(
        paperId: String,
        override: (parent: String?, children: List<com.mcqapp.data.local.CategoryEntity>) -> List<com.mcqapp.data.local.CategoryEntity> = { _, c -> c }
    ) {
        fun preorder(parent: String?): List<com.mcqapp.data.local.CategoryEntity> =
            override(parent, childrenOf(paperId, parent))
                .flatMap { listOf(it) + preorder(it.id) }
        preorder(null).forEachIndexed { index, node ->
            db.categoryDao().updateSortOrder(node.id, index)
        }
    }

    /**
     * Nests a category into the sibling above it, becoming that sibling's
     * LAST child — so the flat question order does not move at all; only
     * the hierarchy changes (the outline → gesture). Refused when the
     * category is its parent's first child: there is no sibling to nest into.
     */
    suspend fun nestCategoryIntoSibling(categoryId: String): Boolean {
        return db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction false
            val siblings = childrenOf(category.paperId, category.parentId)
            val index = siblings.indexOfFirst { it.id == categoryId }
            if (index <= 0) return@withTransaction false
            val newParent = siblings[index - 1]
            db.categoryDao().updateParent(categoryId, newParent.id)
            renumberPaperPreorder(category.paperId)
            Logger.i("REPO", "nestCategory($categoryId into ${newParent.id})")
            true
        }
    }

    /**
     * Promotes a category out of its parent, becoming the parent's next
     * sibling — the outline ← gesture. The flat question order does not
     * move: pre-order places the promoted node right after its former
     * parent's subtree. Refused for roots (nothing to promote out of).
     */
    suspend fun promoteCategoryOutOfParent(categoryId: String): Boolean {
        return db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction false
            val parent = category.parentId ?: return@withTransaction false
            val parentEntity = db.categoryDao().getById(parent) ?: return@withTransaction false
            db.categoryDao().updateParent(categoryId, parentEntity.parentId)
            // Keep the promoted node directly after its former parent in the
            // new bucket: the old pre-order slot lies between the parent and
            // its next sibling, so sorting by it lands it exactly there.
            renumberPaperPreorder(category.paperId)
            Logger.i("REPO", "promoteCategory($categoryId)")
            true
        }
    }

    /**
     * The edit dialog's general reparent: any category under any other
     * category of the same paper (or back to the top level), appended as
     * the new parent's last child. Cycle-guarded — a category cannot become
     * its own descendant's child — and the old parent keeps its remaining
     * children.
     */
    suspend fun reparentCategory(categoryId: String, newParentId: String?): Boolean {
        return db.withTransaction {
            val category = db.categoryDao().getById(categoryId) ?: return@withTransaction false
            if (newParentId == category.parentId) return@withTransaction false
            if (newParentId != null) {
                val newParent = db.categoryDao().getById(newParentId)
                    ?: return@withTransaction false
                if (newParent.paperId != category.paperId) return@withTransaction false
                // No cycles: the new parent must not live in this category's
                // own subtree (which includes the category itself).
                fun subtree(id: String?): Boolean =
                    id == newParentId || childrenOf(category.paperId, id).any { subtree(it.id) }
                if (subtree(categoryId)) return@withTransaction false
                db.categoryDao().updateParent(categoryId, newParentId)
                // Append as the last child, deterministically.
                val last = childrenOf(category.paperId, newParentId)
                    .maxOfOrNull { it.sortOrder } ?: -1
                db.categoryDao().updateSortOrder(categoryId, last + 1)
            } else {
                db.categoryDao().updateParent(categoryId, null)
                val last = childrenOf(category.paperId, null)
                    .maxOfOrNull { it.sortOrder } ?: -1
                db.categoryDao().updateSortOrder(categoryId, last + 1)
            }
            renumberPaperPreorder(category.paperId)
            Logger.i("REPO", "reparentCategory($categoryId -> $newParentId)")
            true
        }
    }

    /** Renames a category; blank titles are refused. */
    suspend fun renameCategory(categoryId: String, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return false
        val category = db.categoryDao().getById(categoryId) ?: return false
        db.categoryDao().upsert(category.copy(title = trimmed))
        Logger.i("REPO", "renameCategory($categoryId -> '$trimmed')")
        return true
    }

    /** Deep-clones a paper (categories, questions, options, keys, marks). */
    suspend fun duplicatePaper(paperId: String): String? {
        // One transaction: a partial clone must not leave a paper shell with
        // half its questions.
        return db.withTransaction {
            val paper = db.paperDao().getById(paperId) ?: return@withTransaction null
            // Only the -copy namespace can collide with the generated paper id.
            val existingPaperIds = db.paperDao()
                .getIdsLike(likePrefixPattern("$paperId-copy")).toSet()
            val newPaperId = com.mcqapp.data.io.PaperClone.copyPaperId(existingPaperIds, paperId)
            db.paperDao().insertIgnore(
                paper.copy(
                    id = newPaperId,
                    title = "${paper.title} (copy)",
                    createdAt = System.currentTimeMillis()
                )
            )
            val remapped = com.mcqapp.data.io.PaperClone.remapCategories(
                db.categoryDao().getByPaper(paperId),
                newPaperId
            )
            remapped.values.forEach { db.categoryDao().insertIgnore(it) }
            // The clone mints `-copy` ids, so the probe scan covers every id a
            // candidate can collide with; ids minted below join the same set.
            val existingQ = db.questionDao().getIdsLike(COPY_ID_PROBE).toMutableSet()
            val questionsByCategory = questions.getQuestionsForCategories(remapped.keys.toList())
                .groupBy { it.categoryId }
            val clones = mutableListOf<com.mcqapp.domain.Question>()
            for ((oldCatId, newCat) in remapped) {
                for (q in questionsByCategory[oldCatId].orEmpty()) {
                    val newQId = com.mcqapp.domain.BulkOps.copyId(existingQ, q.id)
                    existingQ.add(newQId)
                    clones += q.copy(id = newQId, categoryId = newCat.id)
                }
            }
            // Ids are minted for the whole clone first, then written in one
            // pass: the rows are known fresh, so no getQuestion/saveQuestion
            // round-trip is needed to prove each one absent before writing.
            questions.insertCopies(clones)
            Logger.i("REPO", "duplicatePaper($paperId -> $newPaperId)")
            newPaperId
        }
    }
}
