package com.mcqapp.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
@Dao
interface PaperDao {
    @Query("SELECT * FROM papers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PaperEntity>>

    @Query("SELECT * FROM papers ORDER BY createdAt DESC")
    suspend fun getAll(): List<PaperEntity>

    @Query("SELECT * FROM papers WHERE id = :id")
    suspend fun getById(id: String): PaperEntity?

    @Query("SELECT * FROM papers WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PaperEntity>

    /** Ids matching a LIKE pattern (see [likePrefixPattern] in ChunkedQueries). */
    @Query("SELECT id FROM papers WHERE id LIKE :pattern ESCAPE '\\'")
    suspend fun getIdsLike(pattern: String): List<String>

    @Query("SELECT * FROM papers WHERE title = :title ORDER BY createdAt ASC LIMIT 1")
    suspend fun getByTitle(title: String): PaperEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(paper: PaperEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(paper: PaperEntity)

    @Query("UPDATE papers SET title = :title, description = :description, durationMinutes = :durationMinutes, negativeMarking = :negativeMarking WHERE id = :id")
    suspend fun updateFields(id: String, title: String, description: String, durationMinutes: Int, negativeMarking: Double)

    /** Backup restore: creation time rides outside the import format. */
    @Query("UPDATE papers SET createdAt = :createdAt WHERE id = :id")
    suspend fun updateCreatedAt(id: String, createdAt: Long)

    @Query("DELETE FROM papers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE paperId = :paperId ORDER BY sortOrder, title")
    suspend fun getByPaper(paperId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(category: CategoryEntity)

    @Query("UPDATE categories SET paperId = :paperId, title = :title, parentId = :parentId, sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateFields(id: String, paperId: String, title: String, parentId: String?, sortOrder: Int)

    @Query("UPDATE categories SET parentId = :parentId WHERE id = :id")
    suspend fun updateParent(id: String, parentId: String?)

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /**
     * Moves every direct child of [fromParentId] to [toParentId]. Used when a
     * category is deleted: `categories` has no self-referencing foreign key on
     * `parentId`, so deleting a parent would otherwise leave its descendants
     * pointing at a row that no longer exists.
     */
    @Query("UPDATE categories SET parentId = :toParentId WHERE parentId = :fromParentId")
    suspend fun reparentChildren(fromParentId: String, toParentId: String?)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** Slim id + category pair for bulk moves, without loading full question rows. */
data class QuestionCategoryRow(
    val id: String,
    val categoryId: String
)

/**
 * A card row plus its physical `rowid`. The projection exists so the
 * statistics load can keyset-page cards in exactly `getAll()`'s order — the
 * order the overview's tie-broken lists already see — without reading the
 * whole 250k-row table in one go.
 */
data class CardStateRow(
    @Embedded val state: CardStateEntity,
    val rowid: Long
)

/** Scheduling input for a question: its id and stored content hash only. */
data class QuestionIdHash(
    val id: String,
    val contentHash: String
)

@Dao
interface PassageDao {
    @Query("SELECT * FROM passages WHERE id = :id")
    suspend fun getById(id: String): PassageEntity?

    @Query("SELECT * FROM passages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PassageEntity>

    @Query("SELECT * FROM passages WHERE categoryId = :categoryId ORDER BY sortOrder, rowid")
    suspend fun getByCategory(categoryId: String): List<PassageEntity>

    @Query("SELECT * FROM passages")
    suspend fun getAll(): List<PassageEntity>

    @Query("SELECT * FROM passages")
    fun observeAll(): Flow<List<PassageEntity>>

    /** How many questions still point at the passage, for the delete guard. */
    @Query("SELECT COUNT(*) FROM questions WHERE passageId = :passageId")
    suspend fun memberCount(passageId: String): Int

    /** Member count per passage in one grouped read, for the list screen. */
    @Query("SELECT passageId, COUNT(*) AS cnt FROM questions WHERE passageId IS NOT NULL GROUP BY passageId")
    suspend fun memberCountsByPassage(): Map<
        @MapColumn(columnName = "passageId") String,
        @MapColumn(columnName = "cnt") Int
        >

    /**
     * Which questions still point at [passageId], for reassignment and
     * reparenting. Ordered so sequential placement after a move preserves the
     * group's category order deterministically, not by rowid accident.
     */
    @Query("SELECT id FROM questions WHERE passageId = :passageId ORDER BY sortOrder, rowid")
    suspend fun memberIds(passageId: String): List<String>

    /** A question's membership; queried only inside saveQuestion's transaction. */
    @Query("UPDATE questions SET passageId = :passageId WHERE id = :questionId")
    suspend fun assignToMember(questionId: String, passageId: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(passage: PassageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(passage: PassageEntity)

    @Query("UPDATE passages SET categoryId = :categoryId, title = :title, text = :text, image = :image, sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateFields(
        id: String,
        categoryId: String,
        title: String,
        text: String,
        image: String?,
        sortOrder: Int
    )

    @Query("UPDATE passages SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM passages WHERE id = :id")
    suspend fun deleteById(id: String)
}
@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE categoryId = :categoryId ORDER BY sortOrder, rowid")
    suspend fun getByCategory(categoryId: String): List<QuestionEntity>

    @Query(
        "SELECT questions.* FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds) " +
            // Same (sortOrder, title) order as CategoryDao.getByPaper, so the
            // category grouping here matches the drawer's tree order (and
            // getQuestionsForPaper, which feeds it the ids in that order).
            // The (sortOrder, title) pair is what makes the grouping total:
            // addCategory assigns the next slot, but title stays as the
            // defensive tiebreak for any residual equal sortOrders (legacy
            // data), reproducing getByPaper's order. Within a category, the
            // question's own (sortOrder, rowid) keeps its internal order.
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun getByCategories(categoryIds: List<String>): List<QuestionEntity>

    /**
     * One page of bookmarked questions, newest bookmark first (the order the
     * bookmarks screen has always shown). Only requested pages carry the full
     * question content, so a "bookmark everything" bank no longer loads every
     * bookmarked question at once.
     */
    @Query(
        "SELECT questions.* FROM questions " +
            "INNER JOIN bookmarks ON bookmarks.questionId = questions.id " +
            "ORDER BY bookmarks.createdAt DESC, bookmarks.questionId DESC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun getBookmarkedQuestionsPage(offset: Int, limit: Int): List<QuestionEntity>

    /**
     * What a list row needs and nothing more: identity and order, the content
     * hash, a flattened preview of the stem, and the answer-key count — never
     * elements, image or options. Lists that order a paper (study pools, test
     * sessions, the browse list) work off these; the question body is fetched
     * by id for the one row on screen, so a 169k-question paper never
     * materialises its full rows anywhere.
     *
     * The preview is `searchText`, the flattened visible text — the `text`
     * column holds elements JSON, which is for the row reader, not for a row
     * preview (a passage's member list rendered it verbatim).
     *
     * Same (sortOrder, title) order as [getByCategories].
     */
    @Query(
        "SELECT questions.id, questions.categoryId, questions.passageId, " +
            "questions.contentHash, questions.searchText AS text, questions.explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun getRefsByCategories(categoryIds: List<String>): List<QuestionRef>

    /**
     * Scheduling inputs only: id and stored content hash, in the same
     * (sortOrder, title) order as [getRefsByCategories]. Studies read these
     * to resolve card states; no stem, explanation or correlated answer-key
     * count is materialised for a 169k-question paper.
     */
    @Query(
        "SELECT questions.id AS id, questions.contentHash AS contentHash FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun getHashInputsByCategories(categoryIds: List<String>): List<QuestionIdHash>

    /** One question's stored content hash; null when the question exists not. */
    @Query("SELECT contentHash FROM questions WHERE id = :id")
    suspend fun getHashById(id: String): String?

    /** Observation variant of [getRefsByCategories]: list surfaces re-emit. */
    @Query(
        "SELECT questions.id, questions.categoryId, questions.passageId, " +
            "questions.contentHash, questions.searchText AS text, questions.explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    fun observeRefsByCategories(categoryIds: List<String>): kotlinx.coroutines.flow.Flow<List<QuestionRef>>

    /**
     * Browse's list, one window at a time, in the same
     * `(categories.sortOrder, categories.title, questions.sortOrder, rowid)`
     * order as [getRefsByCategories]. Filters are expressed as SQL predicates
     * so a 250k-question paper never materialises its full ref set:
     *
     * - the paper is named by id and joined through `categories`, not by an
     *   `IN` list of its category ids: 1000 categories would be 1000 bind
     *   variables, and SQLite (3.28 and older, i.e. Android 11 and below)
     *   allows 999 in one statement.
     * - `query` is pre-escaped for `LIKE ... ESCAPE '\'`; an empty string
     *   disables the text search. It matches `questions.searchText` — the
     *   flattened visible text — so the predicate never scans the elements
     *   JSON and agrees with the in-memory `searchableText` filter. A question
     *   is also found through its passage's own flattened text (the same
     *   `passages.searchText` the search queries match on), as a subquery
     *   rather than a list of matching passage ids.
     * - `categoryFilterCsv` is `,id,id,…` matched with `INSTR`, so a whole
     *   subtree of categories costs one bind variable instead of one per
     *   category. The `Int` filter flags are 0/1 for reuse of one statement.
     */
    @Query(
        "SELECT questions.id, questions.categoryId, questions.passageId, " +
            "questions.contentHash, questions.searchText AS text, questions.explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE categories.paperId = :paperId " +
            "AND (:query = '' OR questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR " +
            "TRIM(questions.explanation, char(9) || char(10) || char(13) || ' ') = '') " +
            "AND (:uncategorized = 0 OR questions.categoryId = '' " +
            "OR categories.title = 'Uncategorized') " +
            "AND (:categoryFilter = 0 OR " +
            "INSTR(:categoryFilterCsv, ',' || questions.categoryId || ',') > 0) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    fun pagingRefsByCategories(
        paperId: String,
        query: String,
        noAnswer: Int,
        noExplanation: Int,
        uncategorized: Int,
        categoryFilter: Int,
        categoryFilterCsv: String
    ): PagingSource<Int, QuestionRef>

    /**
     * One category's refs in its own order. Reorder math works on a single
     * category's list; fetching it on demand replaces deriving every id from
     * the paper-wide list.
     */
    @Query(
        "SELECT id, categoryId, passageId, contentHash, searchText AS text, explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions WHERE categoryId = :categoryId " +
            "ORDER BY sortOrder, rowid"
    )
    suspend fun getRefsByCategoryOrdered(categoryId: String): List<QuestionRef>

    @Query(
        "SELECT id FROM questions WHERE categoryId = :categoryId " +
            "ORDER BY sortOrder, rowid LIMIT 1"
    )
    suspend fun getFirstRefIdByCategory(categoryId: String): String?

    @Query(
        "SELECT id FROM questions WHERE categoryId = :categoryId " +
            "ORDER BY sortOrder DESC, rowid DESC LIMIT 1"
    )
    suspend fun getLastRefIdByCategory(categoryId: String): String?

    /**
     * The passage's first member, for the Browse row that carries its header.
     * A passage's members share one category (the importer folds strays into
     * the passage's category), so within it the display order is the question
     * order — which makes a `LIMIT 1` read the first member, where the whole
     * ordered passage was read just to take its first row.
     */
    @Query(
        "SELECT id FROM questions WHERE passageId = :passageId " +
            "ORDER BY sortOrder, rowid LIMIT 1"
    )
    suspend fun getFirstRefIdByPassage(passageId: String): String?

    @Query(
        "SELECT COUNT(*) FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds)"
    )
    suspend fun countRefsByCategories(categoryIds: List<String>): Int

    @Query(
        "SELECT questions.id, questions.categoryId, questions.passageId, " +
            "questions.contentHash, questions.searchText AS text, questions.explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.passageId = :passageId " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun getRefsByPassageOrdered(passageId: String): List<QuestionRef>

    /**
     * A passage's member rows, one page at a time. Same projection and order
     * as [getRefsByPassageOrdered] so the list is stable across pages.
     */
    @Query(
        "SELECT questions.id, questions.categoryId, questions.passageId, " +
            "questions.contentHash, questions.searchText AS text, questions.explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.passageId = :passageId " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    fun pagingRefsByPassage(passageId: String): PagingSource<Int, QuestionRef>

    /** The passage's member ids in list order, for the editor's Prev/Next queue. */
    @Query(
        "SELECT questions.id FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.passageId = :passageId " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun getMemberIdsByPassage(passageId: String): List<String>

    /**
     * The same browse filter set, but only its size. The editor's "N of M"
     * reads this instead of materialising 250k ids just to count them.
     */
    @Query(
        "SELECT COUNT(*) FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE categories.paperId = :paperId " +
            "AND (:query = '' OR questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR " +
            "TRIM(questions.explanation, char(9) || char(10) || char(13) || ' ') = '') " +
            "AND (:uncategorized = 0 OR questions.categoryId = '' " +
            "OR categories.title = 'Uncategorized') " +
            "AND (:categoryFilter = 0 OR " +
            "INSTR(:categoryFilterCsv, ',' || questions.categoryId || ',') > 0)"
    )
    suspend fun countBrowseIds(
        paperId: String,
        query: String,
        noAnswer: Int,
        noExplanation: Int,
        uncategorized: Int,
        categoryFilter: Int,
        categoryFilterCsv: String
    ): Int

    /**
     * The id at one position in the browse order, so the editor's Prev/Next
     * can fetch a neighbour without the full filtered list. `OFFSET` walks the
     * ordered index, which is bounded memory even where it is not the fastest
     * read — the alternative was holding every id.
     */
    @Query(
        "SELECT questions.id FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE categories.paperId = :paperId " +
            "AND (:query = '' OR questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR " +
            "TRIM(questions.explanation, char(9) || char(10) || char(13) || ' ') = '') " +
            "AND (:uncategorized = 0 OR questions.categoryId = '' " +
            "OR categories.title = 'Uncategorized') " +
            "AND (:categoryFilter = 0 OR " +
            "INSTR(:categoryFilterCsv, ',' || questions.categoryId || ',') > 0) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid LIMIT 1 OFFSET :offset"
    )
    suspend fun browseIdAtIndex(
        offset: Int,
        paperId: String,
        query: String,
        noAnswer: Int,
        noExplanation: Int,
        uncategorized: Int,
        categoryFilter: Int,
        categoryFilterCsv: String
    ): String?

    /**
     * How many in-scope rows precede [anchorId] in the browse order: its index,
     * for the editor's "N of M". The row-value comparison is keyset, so it does
     * not walk the whole table.
     */
    @Query(
        "SELECT COUNT(*) FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "INNER JOIN questions anchor ON anchor.id = :anchorId " +
            "INNER JOIN categories anchorCategory ON anchorCategory.id = anchor.categoryId " +
            "WHERE categories.paperId = :paperId " +
            "AND (:query = '' OR questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR " +
            "TRIM(questions.explanation, char(9) || char(10) || char(13) || ' ') = '') " +
            "AND (:uncategorized = 0 OR questions.categoryId = '' " +
            "OR categories.title = 'Uncategorized') " +
            "AND (:categoryFilter = 0 OR " +
            "INSTR(:categoryFilterCsv, ',' || questions.categoryId || ',') > 0) " +
            "AND (categories.sortOrder, categories.title, questions.sortOrder, questions.rowid) " +
            "< (anchorCategory.sortOrder, anchorCategory.title, anchor.sortOrder, anchor.rowid)"
    )
    suspend fun browseIndexBefore(
        anchorId: String,
        paperId: String,
        query: String,
        noAnswer: Int,
        noExplanation: Int,
        uncategorized: Int,
        categoryFilter: Int,
        categoryFilterCsv: String
    ): Int

    @Query(
        "SELECT id, categoryId, passageId, contentHash, searchText AS text, explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions WHERE id IN (:ids)"
    )
    suspend fun getRefsByIds(ids: List<String>): List<QuestionRef>

    @Query(
        "SELECT id, categoryId, passageId, contentHash, searchText AS text, explanation, " +
            "(SELECT COUNT(*) FROM correct_answers ca WHERE ca.questionId = questions.id) " +
            "AS answerKeyCount FROM questions WHERE id = :id"
    )
    suspend fun getRefById(id: String): QuestionRef?

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: String): Flow<QuestionEntity?>

    /**
     * Every content hash, column-narrow. Import dedup classification probes
     * this set; materialising the whole `questions` table (element JSON and
     * base64 images included) to read one string column is what made a large
     * re-import OOM.
     */
    @Query("SELECT contentHash FROM questions")
    suspend fun getAllContentHashes(): List<String>

    /** Every question id, column-narrow; collision probes need no body. */
    @Query("SELECT id FROM questions")
    suspend fun getAllIds(): List<String>

    /**
     * Which of [ids] already exists, id-only. The import preview used to load
     * every id in the database to answer this; probing only the ids it holds
     * keeps a large library off the heap while a bank is being parsed.
     */
    @Query("SELECT id FROM questions WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: Collection<String>): List<String>

    /**
     * Per-paper question counts in one grouped read. The storage report used
     * to filter a fully materialised question list per paper.
     */
    @Query(
        "SELECT categories.paperId AS paperId, COUNT(*) AS cnt FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "GROUP BY categories.paperId"
    )
    suspend fun questionCountsByPaper(): Map<
        @MapColumn(columnName = "paperId") String,
        @MapColumn(columnName = "cnt") Int
        >

    /** Per-paper embedded question-image weight (base64 chars ≈ bytes), in SQL. */
    @Query(
        "SELECT categories.paperId AS paperId, " +
            "COALESCE(SUM(LENGTH(questions.image)), 0) + " +
            "COALESCE(SUM(LENGTH(questions.explanationImage)), 0) AS chars " +
            "FROM questions INNER JOIN categories ON questions.categoryId = categories.id " +
            "GROUP BY categories.paperId"
    )
    suspend fun questionImageCharsByPaper(): Map<
        @MapColumn(columnName = "paperId") String,
        @MapColumn(columnName = "chars") Long
        >

    /** Per-paper embedded option-image weight, in SQL. */
    @Query(
        "SELECT categories.paperId AS paperId, " +
            "COALESCE(SUM(LENGTH(options.image)), 0) AS chars " +
            "FROM options INNER JOIN questions ON options.questionId = questions.id " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "GROUP BY categories.paperId"
    )
    suspend fun optionImageCharsByPaper(): Map<
        @MapColumn(columnName = "paperId") String,
        @MapColumn(columnName = "chars") Long
        >

    @Query("SELECT categoryId, COUNT(*) as cnt FROM questions GROUP BY categoryId")
    fun observeCategoryCounts(): Flow<List<CategoryCountEntity>>

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun countQuestions(): Int

    /** One-shot form of [observeCategoryCounts], for a single paper read. */
    @Query("SELECT categoryId, COUNT(*) as cnt FROM questions GROUP BY categoryId")
    suspend fun getCategoryCounts(): List<CategoryCountEntity>

    @Query("SELECT id FROM questions WHERE categoryId = :categoryId")
    suspend fun getIdsByCategory(categoryId: String): List<String>

    /** The stored question with this content, if any: a same-content match. */
    @Query("SELECT id FROM questions WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getIdByContentHash(contentHash: String): String?

    /**
     * Every question sharing a hash, not just the first. A hash is blind to
     * tables and formulas, so two genuinely different questions can collide and
     * only a full content comparison tells them apart.
     */
    @Query("SELECT id FROM questions WHERE contentHash = :contentHash")
    suspend fun getIdsByContentHash(contentHash: String): List<String>

    /**
     * The same, for a whole import's worth of hashes at once. Resolving
     * collisions one question at a time cost three queries per colliding
     * question; a re-import of a large bank paid that thousands of times.
     */
    @Query("SELECT id, contentHash FROM questions WHERE contentHash IN (:hashes)")
    suspend fun getMatchesByContentHashes(hashes: Collection<String>): List<ContentHashMatch>

    /** Everything the content hash deliberately ignores. */
    @Query(
        "UPDATE questions SET explanation = :explanation, marks = :marks, " +
            "difficulty = :difficulty, tags = :tags WHERE id = :id"
    )
    suspend fun updateNonHashedFields(
        id: String,
        explanation: String,
        marks: Double,
        difficulty: String,
        tags: String
    )

    @Query(
        "SELECT questions.id FROM questions INNER JOIN categories " +
            "ON questions.categoryId = categories.id WHERE categories.paperId = :paperId"
    )
    suspend fun getIdsByPaper(paperId: String): List<String>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: String): QuestionEntity?

    /**
     * The passage member with the highest position in [categoryId] other than
     * [excludeId] — the anchor a newly assigned member is placed after, so a
     * group stays contiguous when it grows.
     */
    @Query(
        "SELECT * FROM questions WHERE passageId = :passageId " +
            "AND categoryId = :categoryId AND id != :excludeId " +
            "ORDER BY sortOrder DESC, rowid DESC LIMIT 1"
    )
    suspend fun lastMemberInCategory(
        passageId: String,
        categoryId: String,
        excludeId: String
    ): QuestionEntity?

    /** All members of a passage, grouped by their own category order. */
    @Query(
        "SELECT * FROM questions WHERE passageId = :passageId " +
            "ORDER BY categoryId, sortOrder, rowid"
    )
    suspend fun getMembersByPassage(passageId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<QuestionEntity>

    /** Ids matching a LIKE pattern (see [likePrefixPattern] in ChunkedQueries). */
    @Query("SELECT id FROM questions WHERE id LIKE :pattern ESCAPE '\\'")
    suspend fun getIdsLike(pattern: String): List<String>

    /** Which of [ids] exist, and under which category — nothing else is loaded. */
    @Query("SELECT id, categoryId FROM questions WHERE id IN (:ids)")
    suspend fun getIdCategoriesByIds(ids: List<String>): List<QuestionCategoryRow>

    /**
     * The first page of every question's category, for statistics grouping.
     * The whole-table version this replaced materialised 250k rows into a list
     * on the way to a lookup map; paging keeps the peak at one page. Order is
     * irrelevant to that map, so paging on `id` lets the primary-key index
     * serve the scan.
     *
     * The first page is its own query rather than a sentinel `afterId`, because
     * no `String` is smaller than every question id in SQLite's ordering short
     * of the empty string, and an empty id is legal (if pathological) input.
     */
    @Query("SELECT id, categoryId FROM questions ORDER BY id LIMIT :limit")
    suspend fun pageIdCategoriesFirst(limit: Int): List<QuestionCategoryRow>

    /** One subsequent keyset page; see [pageIdCategoriesFirst]. */
    @Query("SELECT id, categoryId FROM questions WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageIdCategoriesAfter(afterId: String, limit: Int): List<QuestionCategoryRow>

    @Query("UPDATE questions SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun updateCategory(ids: List<String>, categoryId: String): Int

    /**
     * Bulk edits marks/difficulty/tags in one statement; a null bind keeps the
     * column's current value, matching the per-question coalescing the caller
     * used to do in Kotlin over a full delete-and-reinsert per question.
     */
    @Query(
        "UPDATE questions SET marks = COALESCE(:marks, marks), " +
            "difficulty = COALESCE(:difficulty, difficulty), " +
            "tags = COALESCE(:tags, tags) WHERE id IN (:ids)"
    )
    suspend fun updateBulkFields(
        ids: List<String>,
        marks: Double?,
        difficulty: String?,
        tags: String?
    ): Int

    @Query("SELECT MAX(sortOrder) FROM questions WHERE categoryId = :categoryId")
    suspend fun getMaxSortOrder(categoryId: String): Int?

    @Query("UPDATE questions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /**
     * `:fragment` is the longest single word of the query; `:query` is the whole
     * thing. Either may match, so a phrase split by a table cell or a formula
     * still reaches the in-memory filter that understands it. Both LIKEs run
     * against `searchText` — the flattened visible text — not the elements JSON,
     * so the prefilter scans plain text instead of markup for every row. The
     * passage UNION keeps scope QUESTION consistent with [searchIncludingOptions]
     * and the in-memory filter: a question whose only match is in its passage's
     * text must not be dropped by the prefilter here while the filter accepts it
     * elsewhere.
     */
    @Query(
        "SELECT q.* FROM (SELECT DISTINCT questions.*, questions.rowid AS qrowid FROM questions " +
            "WHERE questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tags LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.searchText LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "OR questions.tags LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "UNION SELECT questions.*, questions.rowid FROM questions WHERE passageId IN " +
            "(SELECT id FROM passages WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR searchText LIKE '%' || :fragment || '%' ESCAPE '\\')) q " +
            "ORDER BY q.qrowid DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun search(
        query: String,
        fragment: String,
        limit: Int,
        offset: Int
    ): List<QuestionEntity>

    @Query(
        "SELECT q.* FROM (SELECT DISTINCT questions.*, questions.rowid AS qrowid FROM questions LEFT JOIN options " +
            "ON options.questionId = questions.id WHERE questions.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tags LIKE '%' || :query || '%' ESCAPE '\\' OR options.searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.searchText LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "OR questions.tags LIKE '%' || :fragment || '%' ESCAPE '\\' OR options.searchText LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "UNION SELECT questions.*, questions.rowid FROM questions WHERE passageId IN " +
            "(SELECT id FROM passages WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR searchText LIKE '%' || :fragment || '%' ESCAPE '\\')) q " +
            "ORDER BY q.qrowid DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun searchIncludingOptions(
        query: String,
        fragment: String,
        limit: Int,
        offset: Int
    ): List<QuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: QuestionEntity)

    @Query("UPDATE questions SET contentHash = :contentHash WHERE id = :questionId")
    suspend fun updateContentHash(questionId: String, contentHash: String)

    /** Whole-selection insert; callers use it for minted-fresh copy ids. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(questions: List<QuestionEntity>)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface OptionDao {
    @Query("SELECT * FROM options WHERE questionId = :questionId ORDER BY sortOrder, rowid")
    suspend fun getByQuestion(questionId: String): List<OptionEntity>

    @Query("SELECT * FROM options WHERE questionId IN (:questionIds) ORDER BY sortOrder, rowid")
    suspend fun getForQuestions(questionIds: List<String>): List<OptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(options: List<OptionEntity>)

    @Query("DELETE FROM options WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)
}

@Dao
interface CorrectAnswerDao {
    @Query("SELECT optionId FROM correct_answers WHERE questionId = :questionId")
    suspend fun getCorrectIds(questionId: String): List<String>

    @Query("SELECT * FROM correct_answers WHERE questionId IN (:questionIds)")
    suspend fun getForQuestions(questionIds: List<String>): List<CorrectAnswerEntity>

    @Query("SELECT DISTINCT questionId FROM correct_answers WHERE questionId IN (:questionIds)")
    suspend fun getQuestionIdsWithKeys(questionIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(answers: List<CorrectAnswerEntity>)

    @Query("DELETE FROM correct_answers WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)
}

@Dao
interface CardStateDao {
    @Query("SELECT * FROM card_state WHERE paperId = :paperId")
    suspend fun getByPaper(paperId: String): List<CardStateEntity>

    @Query("SELECT * FROM card_state")
    suspend fun getAll(): List<CardStateEntity>

    /**
     * One keyset page of every card in physical (`rowid`) order — the order
     * [getAll] returns and the order the statistics breakdowns tie-break on.
     * Keyset on `rowid` keeps that order identical while bounding the transient
     * list, and unlike `LIMIT`/`OFFSET` it stays linear over 250k cards.
     */
    @Query("SELECT *, rowid FROM card_state WHERE rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun pageAfterRowId(afterRowId: Long, limit: Int): List<CardStateRow>

    @Query("SELECT COUNT(*) FROM card_state")
    suspend fun countAll(): Int

    @Query("SELECT * FROM card_state WHERE paperId = :paperId AND questionId = :questionId")
    suspend fun get(paperId: String, questionId: String): CardStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: CardStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<CardStateEntity>)

    @Query("DELETE FROM card_state WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)

    @Query("DELETE FROM card_state WHERE questionId IN (:questionIds)")
    suspend fun deleteByQuestions(questionIds: List<String>)

    /**
     * Drops every SM-2 card for a paper. The foreign key already cascades, but
     * deleting explicitly keeps the intent visible and does not depend on the
     * foreign-key pragma being enabled.
     */
    @Query("DELETE FROM card_state WHERE paperId = :paperId")
    suspend fun deleteByPaper(paperId: String)

    @Query("SELECT COUNT(*) FROM card_state WHERE paperId = :paperId")
    suspend fun countByPaper(paperId: String): Int
}

@Dao
interface ReviewLogDao {
    /** Appends one graded review; a row is never updated once written. */
    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    /** Backup restore: rows carry their original ids. */
    @Insert
    suspend fun insertAll(logs: List<ReviewLogEntity>)

    @Query("SELECT * FROM review_log ORDER BY id ASC")
    suspend fun getAll(): List<ReviewLogEntity>

    /**
     * One keyset page of the whole log in append order, for the statistics
     * screen's streaming replay (`FsrsReplay.Stream`). Paging by `id` (the
     * autoincrement primary key, so also each card's chronological order) means
     * a million-row log is never held as entities: only the enriched rows that
     * survive the pass, plus one page, are live at a time.
     */
    @Query("SELECT * FROM review_log WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfterId(afterId: Long, limit: Int): List<ReviewLogEntity>

    /** One question's review history, oldest first. */
    @Query("SELECT * FROM review_log WHERE questionId = :questionId ORDER BY reviewedAt ASC, id ASC")
    suspend fun historyForQuestion(questionId: String): List<ReviewLogEntity>

    /** How many reviews a paper has logged. */
    @Query("SELECT COUNT(*) FROM review_log WHERE paperId = :paperId")
    suspend fun countByPaper(paperId: String): Int

    /**
     * How many reviews exist across every paper, observed: the optimizer's
     * unlock gauge, so the settings button lights up as history accumulates
     * without the screen polling.
     */
    @Query("SELECT COUNT(*) FROM review_log")
    fun observeCount(): Flow<Int>

    /** The one-shot form of [observeCount]: the backup manifest's review count. */
    @Query("SELECT COUNT(*) FROM review_log")
    suspend fun countAll(): Int

    /**
     * Removes one question's logged reviews. The log is immutable per review,
     * but it is not a graveyard: deleting a question, bulk-deleting or moving
     * one across papers drops its schedule and its history in the same
     * transaction, so the log never outlives the card it describes.
     */
    @Query("DELETE FROM review_log WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)

    /** The bulk form of [deleteByQuestion]; the caller keeps ids chunked. */
    @Query("DELETE FROM review_log WHERE questionId IN (:questionIds)")
    suspend fun deleteByQuestions(questionIds: Collection<String>): Int
}

@Dao
interface BookmarkDao {
    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAll(): List<String>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE questionId = :questionId")
    suspend fun remove(questionId: String)

    @Query("DELETE FROM bookmarks WHERE questionId IN (:questionIds)")
    suspend fun removeAll(questionIds: Collection<String>)

    /** How many of [questionIds] are bookmarked, for the delete confirmation. */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE questionId IN (:questionIds)")
    suspend fun countForQuestions(questionIds: Collection<String>): Int

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE questionId = :questionId)")
    suspend fun isBookmarked(questionId: String): Boolean
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Insert
    suspend fun insertResults(results: List<QuestionResultEntity>)

    /**
     * Folds the streamed tallies into the header row after every batch is
     * written, so a multi-batch attempt reports the same counts as the
     * single-batch save.
     */
    @Query(
        "UPDATE attempts SET correctCount = :correct, wrongCount = :wrong, " +
            "skippedCount = :skipped, score = :score, maxScore = :maxScore WHERE id = :id"
    )
    suspend fun updateTally(
        id: Long,
        correct: Int,
        wrong: Int,
        skipped: Int,
        score: Double,
        maxScore: Double
    )

    @Query("SELECT * FROM attempts ORDER BY finishedAt DESC")
    fun observeAll(): Flow<List<AttemptEntity>>

    /** Attempts of one paper only, instead of filtering the whole table in Kotlin. */
    @Query("SELECT * FROM attempts WHERE paperId = :paperId ORDER BY finishedAt ASC")
    suspend fun getByPaper(paperId: String): List<AttemptEntity>

    @Query("SELECT * FROM attempts WHERE id = :id")
    suspend fun getById(id: Long): AttemptEntity?

    /**
     * Each question's most recent graded result across every attempt: the
     * MAX(id) graded row per question wins, regardless of which attempt
     * holds it. Rows with no selection or no answer key carry no evidence,
     * so they are filtered in SQL — the mistake badges used to pull every
     * attempt and every result row (full option JSON included) into memory
     * instead of this single aggregate. Explicit `ORDER BY r.id` pins the
     * scan order the mistake ordering relies on.
     */
    @Query(
        "SELECT r.questionId AS questionId, attempts.paperId AS paperId, " +
            "r.isCorrect AS isCorrect, r.id AS rowId " +
            "FROM question_results r " +
            "INNER JOIN attempts ON r.attemptId = attempts.id " +
            "WHERE r.id IN (SELECT MAX(id) FROM question_results " +
            "WHERE selectedOptionIds != '' AND correctOptionIds != '' GROUP BY questionId) " +
            "ORDER BY r.id"
    )
    suspend fun getLatestStandings(): List<LatestStanding>

    @Query("SELECT * FROM question_results WHERE attemptId = :attemptId ORDER BY rowid")
    suspend fun getResults(attemptId: Long): List<QuestionResultEntity>

    /** One paged window of an attempt, ordered by insertion (`rowid`). */
    @Query(
        "SELECT * FROM question_results WHERE attemptId = :attemptId " +
            "AND id > :afterId ORDER BY rowid LIMIT :limit"
    )
    suspend fun getResultsAfter(
        attemptId: Long,
        afterId: Long,
        limit: Int
    ): List<QuestionResultEntity>

    @Query("SELECT * FROM question_results WHERE id IN (:ids)")
    suspend fun getResultsByIds(ids: List<Long>): List<QuestionResultEntity>

    /**
     * Filter-window scan: only the columns a chip filter needs, so paging a
     * rare filter never decodes every heavy element/option JSON in the attempt.
     */
    @Query(
        "SELECT id, questionId, isCorrect, correctOptionIds, selectedOptionIds " +
            "FROM question_results WHERE attemptId = :attemptId " +
            "AND id > :afterId ORDER BY rowid LIMIT :limit"
    )
    suspend fun scanResults(
        attemptId: Long,
        afterId: Long,
        limit: Int
    ): List<ResultScan>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN correctOptionIds = '' THEN 1 ELSE 0 END), 0) AS ungraded, " +
            "COALESCE(SUM(dwellSeconds), 0) AS totalDwell, " +
            "COALESCE(SUM(CASE WHEN dwellSeconds > 0 THEN 1 ELSE 0 END), 0) AS dwellCount " +
            "FROM question_results WHERE attemptId = :attemptId"
    )
    suspend fun getResultAggregates(attemptId: Long): ResultAggregates

    @Query(
        "SELECT categoryTitle AS title, " +
            "COALESCE(SUM(CASE WHEN correctOptionIds != '' AND isCorrect THEN 1 ELSE 0 END), 0) AS correct, " +
            "COALESCE(SUM(CASE WHEN correctOptionIds != '' THEN 1 ELSE 0 END), 0) AS graded " +
            "FROM question_results WHERE attemptId = :attemptId " +
            "GROUP BY categoryTitle ORDER BY categoryTitle"
    )
    suspend fun getResultCategoryBreakdown(attemptId: Long): List<CategoryBreakdownRow>

    @Query("SELECT * FROM question_results ORDER BY rowid")
    suspend fun getAllResults(): List<QuestionResultEntity>

    @Query("SELECT * FROM attempts ORDER BY finishedAt ASC")
    suspend fun getAllAttempts(): List<AttemptEntity>

    @Query("SELECT COUNT(*) FROM attempts")
    suspend fun countAll(): Int

    @Query("DELETE FROM attempts WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Drops a paper's whole history. question_results cascade from attempts,
     * so one statement clears both.
     */
    @Query("DELETE FROM attempts WHERE paperId = :paperId")
    suspend fun deleteByPaper(paperId: String)

    @Query("SELECT COUNT(*) FROM attempts WHERE paperId = :paperId")
    suspend fun countByPaper(paperId: String): Int
}

@Dao
interface QuestionStatsDao {

    /**
     * Folds one batch of a question's result rows into its lifetime counters.
     * `INSERT OR REPLACE ... SELECT` carries the previous totals forward in a
     * single statement, so it does not need SQLite's 3.24 UPSERT (the app's
     * minSdk 26 ships 3.18).
     */
    @Query(
        "INSERT OR REPLACE INTO question_stats " +
            "(questionId, paperId, paperTitle, categoryTitle, text, " +
            "totalAttempts, gradedCount, correctCount, wrongCount, skipCount, " +
            "ungradedCount, lastResultIsCorrect, lastFinishedAt) " +
            "SELECT :questionId, :paperId, :paperTitle, :categoryTitle, :text, " +
            "COALESCE((SELECT totalAttempts FROM question_stats WHERE questionId = :questionId), 0) + :total, " +
            "COALESCE((SELECT gradedCount FROM question_stats WHERE questionId = :questionId), 0) + :graded, " +
            "COALESCE((SELECT correctCount FROM question_stats WHERE questionId = :questionId), 0) + :correct, " +
            "COALESCE((SELECT wrongCount FROM question_stats WHERE questionId = :questionId), 0) + :wrong, " +
            "COALESCE((SELECT skipCount FROM question_stats WHERE questionId = :questionId), 0) + :skip, " +
            "COALESCE((SELECT ungradedCount FROM question_stats WHERE questionId = :questionId), 0) + :ungraded, " +
            ":isCorrect, :finishedAt"
    )
    suspend fun bumpQuestionStats(
        questionId: String,
        paperId: String,
        paperTitle: String,
        categoryTitle: String,
        text: String,
        total: Int,
        graded: Int,
        correct: Int,
        wrong: Int,
        skip: Int,
        ungraded: Int,
        isCorrect: Boolean,
        finishedAt: Long
    )

    /**
     * Hardest first: highest wrong rate, then most graded attempts, then text.
     * Identical ordering to the legacy in-memory [com.mcqapp.domain.QuestionStats.hardest].
     */
    @Query(
        "SELECT questionId, text, totalAttempts AS attempts, correctCount AS correct, " +
            "wrongCount AS wrong, skipCount AS skipped, ungradedCount AS ungraded " +
            "FROM question_stats WHERE gradedCount > 0 " +
            "ORDER BY (wrongCount * 1.0 / gradedCount) DESC, gradedCount DESC, text ASC " +
            "LIMIT :limit"
    )
    suspend fun getHardestQuestions(limit: Int): List<QuestionStatRow>

    /**
     * Weakest (paper, category) first: lowest rate, then most graded, then
     * titles. Identical ordering to the legacy in-memory
     * [com.mcqapp.domain.Mastery.weakest].
     */
    @Query(
        "SELECT paperId, paperTitle, categoryTitle, " +
            "SUM(correctCount) AS correct, SUM(gradedCount) AS graded " +
            "FROM question_stats GROUP BY paperId, categoryTitle " +
            "HAVING SUM(gradedCount) > 0 " +
            "ORDER BY (SUM(correctCount) * 1.0 / SUM(gradedCount)) ASC, " +
            "SUM(gradedCount) DESC, paperTitle ASC, categoryTitle ASC " +
            "LIMIT :limit"
    )
    suspend fun getWeakestCategories(limit: Int): List<CategoryMasteryRow>
}

/**
 * Folds a batch of just-written result rows into [QuestionStatsDao], one
 * bump per distinct question. Call inside the same transaction as
 * `insertResults` so the aggregate can never drift from the rows.
 */
suspend fun QuestionStatsDao.applyQuestionStats(
    paperId: String,
    paperTitle: String,
    finishedAt: Long,
    rows: List<QuestionResultEntity>
) {
    if (rows.isEmpty()) return
    val grouped = LinkedHashMap<String, MutableList<QuestionResultEntity>>()
    for (row in rows) grouped.getOrPut(row.questionId) { mutableListOf() }.add(row)
    for ((questionId, list) in grouped) {
        var graded = 0
        var correct = 0
        var wrong = 0
        var skip = 0
        var ungraded = 0
        for (row in list) {
            if (row.correctOptionIds.isEmpty()) {
                ungraded++
            } else {
                graded++
                when {
                    row.selectedOptionIds.isEmpty() -> skip++
                    row.isCorrect -> correct++
                    else -> wrong++
                }
            }
        }
        val last = list.last()
        bumpQuestionStats(
            questionId = questionId,
            paperId = paperId,
            paperTitle = paperTitle,
            categoryTitle = last.categoryTitle,
            text = last.text,
            total = list.size,
            graded = graded,
            correct = correct,
            wrong = wrong,
            skip = skip,
            ungraded = ungraded,
            isCorrect = last.isCorrect,
            finishedAt = finishedAt
        )
    }
}

/** One row of [QuestionStatsDao.getHardestQuestions]. */
data class QuestionStatRow(
    val questionId: String,
    val text: String,
    val attempts: Int,
    val correct: Int,
    val wrong: Int,
    val skipped: Int,
    val ungraded: Int
)

/** One row of [QuestionStatsDao.getWeakestCategories]. */
data class CategoryMasteryRow(
    val paperId: String,
    val paperTitle: String,
    val categoryTitle: String,
    val correct: Int,
    val graded: Int
)

/** One row of [QuestionDao.getMatchesByContentHashes]. */
data class ContentHashMatch(val id: String, val contentHash: String)

/** The chip-filter columns of one result row, without its content payload. */
data class ResultScan(
    val id: Long,
    val questionId: String,
    val isCorrect: Boolean,
    val correctOptionIds: String,
    val selectedOptionIds: String
)

/** One-row header aggregate for the results screen. */
data class ResultAggregates(
    val total: Int = 0,
    val ungraded: Int = 0,
    val totalDwell: Long = 0,
    val dwellCount: Int = 0
)

/** One category's graded tally in [AttemptDao.getResultCategoryBreakdown]. */
data class CategoryBreakdownRow(
    val title: String,
    val correct: Int,
    val graded: Int
)

/**
 * A question's scheduling-and-list columns, with the flattened stem for
 * previews. Never carries elements, images or options, so a whole paper of
 * these is tens of megabytes rather than hundreds.
 */
data class QuestionRef(
    val id: String,
    val categoryId: String,
    val passageId: String? = null,
    val contentHash: String = "",
    /**
     * Flattened preview of the stem, for a list row. The ref queries select
     * `searchText`; the `text` column holds elements JSON and must not be
     * rendered as if it were a stem.
     */
    val text: String = "",
    val explanation: String = "",
    val answerKeyCount: Int = 0
)

/**
 * One row of [AttemptDao.getLatestStandings]: a question's latest graded
 * standing. [rowId] orders standings by recency, the same key the old
 * rowid-ordered full scan used.
 */
data class LatestStanding(
    val questionId: String,
    val paperId: String,
    val isCorrect: Boolean,
    val rowId: Long
)
