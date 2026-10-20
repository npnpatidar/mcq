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

/**
 * One question's position inside its category: only what a reorder needs to
 * validate and renumber. Selecting the whole row would read the stem, image
 * and explanation JSON of every row of the category — megabytes per thousand.
 */
data class QuestionOrderRow(
    val id: String,
    val sortOrder: Int
)

/**
 * One question row with the cursor column the export walk orders it by.
 * `questions` orders by `(sortOrder, rowid)`, and only `rowid` is not already
 * on [QuestionEntity]. Named `rowid` so Room maps it the same way
 * [CardStateRow] does.
 */
data class QuestionPageRow(
    @Embedded val question: QuestionEntity,
    val rowid: Long
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

    /** One-row probe: fires on any passage write without loading the table. */
    @Query("SELECT id FROM passages LIMIT 1")
    fun observeAnyPassage(): Flow<String?>

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

    /**
     * A category's positions only (`id`, `sortOrder`), in display order — what
     * a block reorder validates and renumbers against. The whole-row
     * [getByCategory] was selected just to read these two fields, dragging the
     * stem, image and explanation JSON of every row of the category along.
     */
    @Query("SELECT id, sortOrder FROM questions WHERE categoryId = :categoryId ORDER BY sortOrder, rowid")
    suspend fun getOrderByCategory(categoryId: String): List<QuestionOrderRow>

    /** A question's category id without reading its content. */
    @Query("SELECT categoryId FROM questions WHERE id = :id")
    suspend fun getCategoryIdById(id: String): String?

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
     * One keyset page of a category's full rows — the reader the export walk
     * uses. A document has to read every question it prints, but it never has
     * to hold more than the page it is writing, so this mirrors
     * [pageSessionRefsByCategory] for the rows that carry content. OFFSET
     * would rescan from the top of a category a million rows deep.
     */
    @Query(
        "SELECT *, rowid FROM questions WHERE categoryId = :categoryId " +
            "AND (sortOrder > :afterSortOrder " +
            "  OR (sortOrder = :afterSortOrder AND rowid > :afterRowId)) " +
            "ORDER BY sortOrder, rowid LIMIT :limit"
    )
    suspend fun pageByCategory(
        categoryId: String,
        afterSortOrder: Int,
        afterRowId: Long,
        limit: Int
    ): List<QuestionPageRow>

    /** How many questions the given categories hold together, for an export's
     * header count. Chunked at the call site: a paper may hold 1000 of them. */
    @Query("SELECT COUNT(*) FROM questions WHERE categoryId IN (:categoryIds)")
    suspend fun countInCategories(categoryIds: List<String>): Int

    /** One page of bookmarked questions, newest bookmark first (the order the
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
     * The distinct paper titles the bookmarked questions came from, sorted, for
     * the bookmark export's category headings. A bookmark whose question or
     * paper no longer resolves folds into "Other", exactly as the in-memory
     * builder did.
     */
    @Query(
        "SELECT DISTINCT COALESCE(papers.title, 'Other') AS title " +
            "FROM bookmarks " +
            "INNER JOIN questions ON questions.id = bookmarks.questionId " +
            "LEFT JOIN categories ON categories.id = questions.categoryId " +
            "LEFT JOIN papers ON papers.id = categories.paperId " +
            "ORDER BY title"
    )
    suspend fun getBookmarkedPaperTitles(): List<String>

    /** How many bookmarks still resolve to a question; dangling ids are dropped. */
    @Query(
        "SELECT COUNT(*) FROM bookmarks " +
            "INNER JOIN questions ON questions.id = bookmarks.questionId"
    )
    suspend fun countBookmarkedQuestions(): Int

    /**
     * One keyset page of a title group's bookmarked questions, most recently
     * bookmarked first. [afterQuestionId] is null for the first page; the
     * `(createdAt, questionId)` key is then passed back for the next one, so a
     * title group is never materialised whole and only one page of content is
     * decoded at a time.
     */
    @Query(
        "SELECT questions.*, bookmarks.createdAt AS bookmarkCreatedAt " +
            "FROM questions " +
            "INNER JOIN bookmarks ON bookmarks.questionId = questions.id " +
            "LEFT JOIN categories ON categories.id = questions.categoryId " +
            "LEFT JOIN papers ON papers.id = categories.paperId " +
            "WHERE COALESCE(papers.title, 'Other') = :title " +
            "AND (:afterQuestionId IS NULL OR bookmarks.createdAt < :afterCreatedAt " +
            "OR (bookmarks.createdAt = :afterCreatedAt AND bookmarks.questionId < :afterQuestionId)) " +
            "ORDER BY bookmarks.createdAt DESC, bookmarks.questionId DESC LIMIT :limit"
    )
    suspend fun pageBookmarkedQuestionsForTitle(
        title: String,
        afterCreatedAt: Long,
        afterQuestionId: String?,
        limit: Int
    ): List<BookmarkedQuestionRow>

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
     * A session's rows, ids and passage membership only, one keyset page at a
     * time.
     *
     * A test session walks its order by index and resolves the one question on
     * screen by id, so nothing else about a row is ever read. The full ref
     * query below also materialises the stem preview and the explanation
     * elements JSON per row: ~61 MB of strings for a million questions on the
     * synthetic bank, and an explanation can hold an inline data-URI image, so
     * kilobytes per row on a real one — all of it for fields no session code
     * reads. [SessionRefRow] carries the id, the passage, and the two cursor
     * columns, so a session order is built without ever holding the rows.
     *
     * A category's rows are ordered `(sortOrder, rowid)`, exactly as
     * [getRefsByCategoryOrdered] orders them, and a whole paper concatenates
     * its categories in `(sortOrder, title)` — the order the join this
     * replaced used, so the streaming build reproduces it. The keyset
     * predicate walks a category without an OFFSET, which would rescan from
     * the top on every page of a category a million rows deep;
     * `Int.MIN_VALUE`/`Long.MIN_VALUE` is below every stored value, so the
     * same statement serves the first page.
     */
    @Query(
        "SELECT id, passageId, sortOrder, rowid AS rowId FROM questions " +
            "WHERE categoryId = :categoryId " +
            "AND (sortOrder > :afterSortOrder " +
            "  OR (sortOrder = :afterSortOrder AND rowid > :afterRowId)) " +
            "ORDER BY sortOrder, rowid LIMIT :limit"
    )
    suspend fun pageSessionRefsByCategory(
        categoryId: String,
        afterSortOrder: Int,
        afterRowId: Long,
        limit: Int
    ): List<SessionRefRow>

    /**
     * The same slim rows for a known id list, in its own order — a
     * mistakes-only session orders by history, not by category.
     */
    @Query("SELECT id, passageId FROM questions WHERE id IN (:ids)")
    suspend fun getSessionRefsByIds(ids: List<String>): List<SessionRef>

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

    /** How many questions the paper has, nested categories included. */
    @Query(
        "SELECT COUNT(*) FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE categories.paperId = :paperId"
    )
    suspend fun countForPaper(paperId: String): Int

    /**
     * How many of the paper's questions have no answer key — excluded from
     * scoring entirely. One aggregate query, so the submit summary of a
     * million-question paper does not count a million ids in Kotlin.
     */
    @Query(
        "SELECT COUNT(*) FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE categories.paperId = :paperId " +
            "AND NOT EXISTS (SELECT 1 FROM correct_answers ca " +
            "WHERE ca.questionId = questions.id)"
    )
    suspend fun countUngradedForPaper(paperId: String): Int

    /** [countUngradedForPaper] over an explicit category selection. */
    @Query(
        "SELECT COUNT(*) FROM questions WHERE categoryId IN (:categoryIds) " +
            "AND NOT EXISTS (SELECT 1 FROM correct_answers ca " +
            "WHERE ca.questionId = questions.id)"
    )
    suspend fun countUngradedForCategories(categoryIds: List<String>): Int

    /**
     * The first question of the paper a session would serve as *new*, in the
     * authored order [getHashInputsByCategories] uses.
     *
     * A question is new in the three cases [Study.pools] treats as fresh: it
     * has no schedule at all, its schedule is still new (no reps, no due
     * date), or its schedule's content hash no longer matches the question's —
     * an edited question is scheduled from scratch, which makes it new again.
     *
     * The session serves new cards one at a time, so the pull reads the first
     * of them; a paper of a million questions must not be materialised to hand
     * over one card.
     */
    @Query(
        "SELECT questions.id FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "LEFT JOIN card_state ON card_state.questionId = questions.id " +
            "  AND card_state.paperId = categories.paperId " +
            "WHERE categories.paperId = :paperId " +
            "AND (card_state.questionId IS NULL " +
            "  OR (card_state.reps = 0 AND card_state.dueAt = 0) " +
            "  OR card_state.contentHash != questions.contentHash) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid LIMIT 1"
    )
    suspend fun firstNewQuestionForPaper(paperId: String): String?

    /**
     * Every question of the paper a session would serve as new, in authored
     * order — the full study queue's fresh pool. Linear in the paper by
     * definition; the pull uses [firstNewQuestionForPaper] instead.
     */
    @Query(
        "SELECT questions.id FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "LEFT JOIN card_state ON card_state.questionId = questions.id " +
            "  AND card_state.paperId = categories.paperId " +
            "WHERE categories.paperId = :paperId " +
            "AND (card_state.questionId IS NULL " +
            "  OR (card_state.reps = 0 AND card_state.dueAt = 0) " +
            "  OR card_state.contentHash != questions.contentHash) " +
            "ORDER BY categories.sortOrder, categories.title, " +
            "questions.sortOrder, questions.rowid"
    )
    suspend fun newQuestionsForPaper(paperId: String): List<String>

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
     *   disables the term clauses (`:query = ''`). It matches the
     *   Kotlin-folded `questions.searchTextFolded` — the folded visible text
     *   — so the predicate never scans the elements JSON, never rescans
     *   markup, and folds every letter the way the in-memory
     *   `searchableText` filter does (SQLite's `LIKE` folds A-Z only, which
     *   vetoed a `CAFÉ` row against a `café` query). It also matches the
     *   folded `questions.tags` (`tagsFolded`) and each option's own folded
     *   `searchText`, the same three fields the global search reads and the
     *   UI promises ("Search text, tags, options"). A question is also found
     *   through its passage's own folded text (the same
     *   `passages.searchTextFolded` the search queries match on), as a
     *   subquery rather than a list of matching passage ids.
     * - `noExplanation` tests the *serialized* explanation against the shapes
     *   the app writes: the editor stores `[]` for an unanswered explanation
     *   and both importers store `[{"type":"text","content":""}]` when the
     *   file carried no explanation text, so the column is never `''`.
     *   Comparing the flattened text would mean decoding the elements JSON per
     *   row, which SQL cannot do here.
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
            "AND (:query = '' OR questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM options o WHERE o.questionId = questions.id " +
            "AND o.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\') " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR questions.explanation IS NULL " +
            "OR TRIM(questions.explanation) IN " +
            "('', '[]', '[{\"type\":\"text\",\"content\":\"\"}]')) " +
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
     * One category's reorder order: its questions' ids and passage ids, in
     * `(sortOrder, rowid)` order. That is all
     * [com.mcqapp.domain.PassageReorder] reads, and a category is not bounded
     * in a paper: selecting the stem preview and the explanation elements JSON
     * here materialised a whole large category's content — an explanation can
     * hold an inline data-URI image — just to move one row.
     *
     * The remaining [QuestionRef] fields are selected as constants, so the
     * result type stays the one the reorder math already takes while the
     * columns themselves are never read.
     */
    @Query(
        "SELECT id, categoryId, passageId, '' AS contentHash, '' AS text, " +
            "'' AS explanation, 0 AS answerKeyCount FROM questions " +
            "WHERE categoryId = :categoryId ORDER BY sortOrder, rowid"
    )
    suspend fun getRefsByCategoryOrdered(categoryId: String): List<QuestionRef>

    /**
     * The distinct passages this category's questions refer to. Export needs
     * membership, not the members, and it used to read a whole slim ref list
     * per category just to take the passage ids off it.
     */
    @Query(
        "SELECT DISTINCT passageId FROM questions " +
            "WHERE categoryId = :categoryId AND passageId IS NOT NULL"
    )
    suspend fun getPassageIdsByCategory(categoryId: String): List<String>

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
            "AND (:query = '' OR questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM options o WHERE o.questionId = questions.id " +
            "AND o.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\') " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR questions.explanation IS NULL " +
            "OR TRIM(questions.explanation) IN " +
            "('', '[]', '[{\"type\":\"text\",\"content\":\"\"}]')) " +
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
            "AND (:query = '' OR questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM options o WHERE o.questionId = questions.id " +
            "AND o.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\') " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR questions.explanation IS NULL " +
            "OR TRIM(questions.explanation) IN " +
            "('', '[]', '[{\"type\":\"text\",\"content\":\"\"}]')) " +
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
            "AND (:query = '' OR questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR EXISTS (SELECT 1 FROM options o WHERE o.questionId = questions.id " +
            "AND o.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\') " +
            "OR questions.passageId IN (SELECT id FROM passages " +
            "WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\')) " +
            "AND (:noAnswer = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM correct_answers ca WHERE ca.questionId = questions.id)) " +
            "AND (:noExplanation = 0 OR questions.explanation IS NULL " +
            "OR TRIM(questions.explanation) IN " +
            "('', '[]', '[{\"type\":\"text\",\"content\":\"\"}]')) " +
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

    /**
     * Per-category counts for the whole bank, for the library tree.
     *
     * This is the one aggregate left on a hot path, and it stays because the
     * library genuinely shows every paper at once: one covering-index scan
     * (~90 ms warm for a million questions) is cheaper than a query per paper,
     * and the index is already there for browse. Everything that only needed
     * the *trigger* watches [observeAnyQuestion] instead, and a single paper's
     * tree reads [getCategoryCountsFor].
     */
    @Query("SELECT categoryId, COUNT(*) as cnt FROM questions GROUP BY categoryId")
    fun observeCategoryCounts(): Flow<List<CategoryCountEntity>>

    /**
     * A one-row probe for flows that only need to know *that* a question was
     * written. It stands in for watching an aggregate: the `GROUP BY` above
     * re-scans every row on each write (a million-row paper paid it on every
     * editor save), while this reads one row — Room re-emits on any write to
     * the table either way.
     */
    @Query("SELECT id FROM questions LIMIT 1")
    fun observeAnyQuestion(): Flow<String?>

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun countQuestions(): Int

    /**
     * Per-category counts for one paper's categories.
     *
     * The whole-bank form of this query is a covering-index scan of a million
     * entries (~90 ms warm here, and it only grows), and a screen only ever
     * shows one paper's tree: reading a 100-question paper this way costs
     * microseconds instead. The category ids come from that paper's own rows,
     * so nothing outside it is counted.
     */
    @Query(
        "SELECT categoryId, COUNT(*) as cnt FROM questions " +
            "WHERE categoryId IN (:categoryIds) GROUP BY categoryId"
    )
    suspend fun getCategoryCountsFor(categoryIds: List<String>): List<CategoryCountEntity>

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
            "difficulty = :difficulty, tags = :tags, tagsFolded = :tagsFolded, " +
            "image = :image, explanationImage = :explanationImage WHERE id = :id"
    )
    suspend fun updateNonHashedFields(
        id: String,
        explanation: String,
        marks: Double,
        difficulty: String,
        tags: String,
        /**
         * [tags] Kotlin-folded by the caller, the same string that is bound to
         * [tags]: the duplicate-refresh path rewrites `tags` without touching
         * the folded twin, so a re-imported `CAFÉ` tag left `tagsFolded` at the
         * previous value forever — `café` missed the row and `alpha` matched a
         * dead tag. Never folded in SQL; `lower()` is the bug's A–Z fold.
         */
        tagsFolded: String,
        image: String?,
        explanationImage: String?
    )

    /**
     * How many questions the paper holds, by the same join the paper's id list
     * used. The delete confirmation took `.size` of that list; a
     * million-question paper paid for the ids just to count them.
     */
    @Query(
        "SELECT COUNT(*) FROM questions INNER JOIN categories " +
            "ON questions.categoryId = categories.id WHERE categories.paperId = :paperId"
    )
    suspend fun countByPaper(paperId: String): Int

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
     * The first page of one paper's questions and their categories, for the
     * scoped statistics grouping. Only the selected paper is read: the whole
     * table would be one entry per question in the bank, which is the
     * whole-bank structure the statistics screen exists to avoid. Order is
     * irrelevant to that map, so paging on `id` lets the primary-key index
     * serve the scan while the categories join scopes it to the paper.
     *
     * The first page is its own query rather than a sentinel `afterId`, because
     * no `String` is smaller than every question id in SQLite's ordering short
     * of the empty string, and an empty id is legal (if pathological) input.
     */
    @Query(
        "SELECT q.id, q.categoryId FROM questions q " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE c.paperId = :paperId ORDER BY q.id LIMIT :limit"
    )
    suspend fun pageIdCategoriesFirst(paperId: String, limit: Int): List<QuestionCategoryRow>

    /** One subsequent keyset page; see [pageIdCategoriesFirst]. */
    @Query(
        "SELECT q.id, q.categoryId FROM questions q " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE c.paperId = :paperId AND q.id > :afterId ORDER BY q.id LIMIT :limit"
    )
    suspend fun pageIdCategoriesAfter(
        paperId: String,
        afterId: String,
        limit: Int
    ): List<QuestionCategoryRow>

    @Query("UPDATE questions SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun updateCategory(ids: List<String>, categoryId: String): Int

    /**
     * Bulk edits marks/difficulty/tags in one statement; a null bind keeps the
     * column's current value, matching the per-question coalescing the caller
     * used to do in Kotlin over a full delete-and-reinsert per question.
     *
     * `tags` and `tagsFolded` are one pair: a non-null [tagsFolded] is the
     * Kotlin lowercase of [tags] (never SQL `lower()`), folded by the caller,
     * and a null pair keeps both columns — so a bulk edit to `CAFÉ` cannot
     * leave the folded twin at its previous value.
     */
    @Query(
        "UPDATE questions SET marks = COALESCE(:marks, marks), " +
            "difficulty = COALESCE(:difficulty, difficulty), " +
            "tags = COALESCE(:tags, tags), " +
            "tagsFolded = COALESCE(:tagsFolded, tagsFolded) WHERE id IN (:ids)"
    )
    suspend fun updateBulkFields(
        ids: List<String>,
        marks: Double?,
        difficulty: String?,
        tags: String?,
        tagsFolded: String?
    ): Int

    @Query("SELECT MAX(sortOrder) FROM questions WHERE categoryId = :categoryId")
    suspend fun getMaxSortOrder(categoryId: String): Int?

    @Query("UPDATE questions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /**
     * `:fragment` is the longest single word of the query; `:query` is the whole
     * thing. Either may match, so a phrase split by a table cell or a formula
     * still reaches the in-memory filter that understands it. Both LIKEs run
     * against the Kotlin-folded columns — `questions.searchTextFolded`,
     * `tagsFolded`, each option's folded `searchText` — against a
     * Kotlin-lowercased bind, not the elements JSON, so the prefilter scans
     * plain text instead of markup for every row *and* folds every letter
     * the way the in-memory authority does. The
     * passage UNION keeps scope QUESTION consistent with [searchIncludingOptions]
     * and the in-memory filter: a question whose only match is in its passage's
     * text must not be dropped by the prefilter here while the filter accepts it
     * elsewhere.
     */
    @Query(
        "SELECT q.* FROM (SELECT DISTINCT questions.*, questions.rowid AS qrowid FROM questions " +
            "WHERE questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.searchTextFolded LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "UNION SELECT questions.*, questions.rowid FROM questions WHERE passageId IN " +
            "(SELECT id FROM passages WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR searchTextFolded LIKE '%' || :fragment || '%' ESCAPE '\\')) q " +
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
            "ON options.questionId = questions.id WHERE questions.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :query || '%' ESCAPE '\\' OR options.searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.searchTextFolded LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "OR questions.tagsFolded LIKE '%' || :fragment || '%' ESCAPE '\\' OR options.searchTextFolded LIKE '%' || :fragment || '%' ESCAPE '\\' " +
            "UNION SELECT questions.*, questions.rowid FROM questions WHERE passageId IN " +
            "(SELECT id FROM passages WHERE searchTextFolded LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR searchTextFolded LIKE '%' || :fragment || '%' ESCAPE '\\')) q " +
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

/**
 * A paper's schedules, counted in SQL by [CardStateDao.countsForPaper].
 * `newish` is the row side of the fresh pool: rows the session would serve as
 * new; `storedRows` counts the paper's rows so the caller can subtract them
 * from the paper's question count to get the questions with no schedule yet.
 */
data class StudyPopulation(
    val learning: Int,
    val due: Int,
    val leeches: Int,
    val newish: Int,
    val storedRows: Int
)

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

    /**
     * Scheduling for a known set of questions — what an Anki export needs for
     * the notes it is about to write. Reading the paper's whole `card_state`
     * instead was every studied card the paper has: ~100 MB of entities at a
     * million.
     */
    @Query("SELECT * FROM card_state WHERE questionId IN (:ids)")
    suspend fun getForQuestions(ids: List<String>): List<CardStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: CardStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<CardStateEntity>)

    @Query("DELETE FROM card_state WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)

    @Query("DELETE FROM card_state WHERE questionId IN (:questionIds)")
    suspend fun deleteByQuestions(questionIds: List<String>)

    /**
     * Drops every card whose question lives in one category, resolved as a
     * subquery: a category can hold a million questions, and the relation is a
     * join, so the ids are never listed. Must run before the category (and its
     * cascade) is deleted; the FK here points at the paper, not the question.
     */
    @Query(
        "DELETE FROM card_state WHERE questionId IN " +
            "(SELECT id FROM questions WHERE categoryId = :categoryId)"
    )
    suspend fun deleteAllForCategory(categoryId: String): Int

    /**
     * Drops every SM-2 card for a paper. The foreign key already cascades, but
     * deleting explicitly keeps the intent visible and does not depend on the
     * foreign-key pragma being enabled.
     */
    @Query("DELETE FROM card_state WHERE paperId = :paperId")
    suspend fun deleteByPaper(paperId: String)

    @Query("SELECT COUNT(*) FROM card_state WHERE paperId = :paperId")
    suspend fun countByPaper(paperId: String): Int

    /**
     * A paper's schedules counted by pool, without resolving a card state per
     * question. The predicates are [Study.pools]': a card is new while it has
     * no schedule, its schedule is still new (no reps, no due date), or its
     * stored hash no longer matches its question — an edited question is
     * scheduled from scratch, so counting it as due first would advertise a
     * card the pull then resets. A due leech is a leech and never also due,
     * the way [StudyEligibility] splits them. The join through questions drops
     * a schedule whose question is gone, so a deleted question stops counting.
     *
     * `paperId` is the index key here; the categories join only proves the
     * question still belongs to the paper. A paper with no rows yields one
     * all-zero row (the COALESCEs keep the sums from coming back null).
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN cs.dueAt <= :now " +
            "  AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "  AND cs.contentHash = q.contentHash " +
            "  AND cs.cardState IN ('LEARNING','RELEARNING') AND cs.leech = 0 " +
            "  THEN 1 ELSE 0 END), 0) AS learning, " +
            "COALESCE(SUM(CASE WHEN cs.dueAt <= :now " +
            "  AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "  AND cs.contentHash = q.contentHash " +
            "  AND cs.cardState NOT IN ('LEARNING','RELEARNING') AND cs.leech = 0 " +
            "  THEN 1 ELSE 0 END), 0) AS due, " +
            "COALESCE(SUM(CASE WHEN cs.dueAt <= :now " +
            "  AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "  AND cs.contentHash = q.contentHash AND cs.leech = 1 " +
            "  THEN 1 ELSE 0 END), 0) AS leeches, " +
            "COALESCE(SUM(CASE WHEN (cs.reps = 0 AND cs.dueAt = 0) " +
            "  OR cs.contentHash != q.contentHash THEN 1 ELSE 0 END), 0) AS newish, " +
            "COUNT(*) AS storedRows " +
            "FROM card_state cs " +
            "INNER JOIN questions q ON q.id = cs.questionId " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE cs.paperId = :paperId AND c.paperId = :paperId"
    )
    suspend fun countsForPaper(paperId: String, now: Long): StudyPopulation

    /**
     * The paper's schedules whose stored hash no longer matches their
     * question, with the hash it should hold. Bounded by how many questions
     * were edited, which is why persisting the reset is affordable — it is the
     * *unseen* questions that must not be seeded, one row per question of the
     * paper.
     */
    @Query(
        "SELECT q.id AS id, q.contentHash AS contentHash FROM card_state cs " +
            "INNER JOIN questions q ON q.id = cs.questionId " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE cs.paperId = :paperId AND c.paperId = :paperId " +
            "AND cs.contentHash != q.contentHash"
    )
    suspend fun staleForPaper(paperId: String): List<QuestionIdHash>

    /**
     * The paper's schedules that are due now and still describe their
     * question's current text, in authored order — the learning and review
     * pools' source once a paper is too large to hold as a model. Rows whose
     * hash no longer matches are left out on purpose: they are new again, and
     * the new-question query serves them.
     */
    @Query(
        "SELECT cs.* FROM card_state cs " +
            "INNER JOIN questions q ON q.id = cs.questionId " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE cs.paperId = :paperId AND c.paperId = :paperId " +
            "AND cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "AND cs.contentHash = q.contentHash " +
            "ORDER BY c.sortOrder, c.title, q.sortOrder, q.rowid"
    )
    suspend fun dueForPaper(paperId: String, now: Long): List<CardStateEntity>

    /**
     * The first due learning card in authored order — the head of `Study.pools`'
     * learning pool. `Study.pickNext` only ever reads the first learning card,
     * so the pull reads it with `LIMIT 1` rather than resolving every due
     * schedule; a paper with a large due backlog must not be materialised to
     * serve one card.
     */
    @Query(
        "SELECT cs.* FROM card_state cs " +
            "INNER JOIN questions q ON q.id = cs.questionId " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE cs.paperId = :paperId AND c.paperId = :paperId " +
            "AND cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "AND cs.contentHash = q.contentHash " +
            "AND cs.cardState IN ('LEARNING','RELEARNING') " +
            "ORDER BY c.sortOrder, c.title, q.sortOrder, q.rowid LIMIT 1"
    )
    suspend fun firstDueLearningForPaper(paperId: String, now: Long): CardStateEntity?

    /**
     * The due review card `Study.pools` would rank first: the one whose
     * predicted retention is lowest. Retention is a strictly decreasing
     * function of `elapsed / stability`, so the ranking is that ratio
     * descending, computed in SQL so `LIMIT 1` reads one row instead of the
     * whole backlog. `stability <= 0` is retention 0 and sorts ahead of every
     * positive ratio; ties fall to the oldest due date then authored order,
     * the same comparator `Study.pools` uses. [dayMs] converts the millisecond
     * gap to days so both branches of the ratio share one unit.
     *
     * A due leech is an ordinary review here, exactly as in `Study.pools`; the
     * leech drill filters them separately.
     */
    @Query(
        "SELECT cs.* FROM card_state cs " +
            "INNER JOIN questions q ON q.id = cs.questionId " +
            "INNER JOIN categories c ON c.id = q.categoryId " +
            "WHERE cs.paperId = :paperId AND c.paperId = :paperId " +
            "AND cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0) " +
            "AND cs.contentHash = q.contentHash " +
            "AND cs.cardState NOT IN ('LEARNING','RELEARNING') " +
            "ORDER BY " +
            "CASE WHEN cs.stability <= 0.0 THEN 0 ELSE 1 END ASC, " +
            "CASE WHEN cs.stability <= 0.0 THEN 0.0 " +
            "WHEN cs.lastReviewedAt = 0 " +
            "THEN MAX(cs.intervalDays, 1) * 1.0 / cs.stability " +
            "ELSE (:now - cs.lastReviewedAt) * 1.0 / (:dayMs * cs.stability) END DESC, " +
            "cs.dueAt ASC, " +
            "c.sortOrder, c.title, q.sortOrder, q.rowid LIMIT 1"
    )
    suspend fun bestDueReviewForPaper(
        paperId: String,
        now: Long,
        dayMs: Double
    ): CardStateEntity?
}

/**
 * The in-progress test session's answers, one row per question reached. See
 * [TestProgressEntity]: the session's header (seeds, scope, index, timers) stays
 * in the resume snapshot, while what was answered is written here a row at a
 * time so one answer costs one small upsert.
 */
@Dao
interface TestProgressDao {
    /**
     * Writes the questions that just changed — one row each, no read first.
     * The whole change set of one action is one or two rows, never the sitting.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TestProgressEntity>)

    /** Every answer of a saved session, for the resume offer. */
    @Query("SELECT * FROM test_progress WHERE paperId = :paperId")
    suspend fun getForPaper(paperId: String): List<TestProgressEntity>

    @Query("DELETE FROM test_progress WHERE paperId = :paperId")
    suspend fun deleteForPaper(paperId: String)
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

    /**
     * One keyset page of the log's distinct question ids, in id order. The
     * optimizer builds one training card per question and a card needs all of
     * its rows: paging *questions* first lets the rows arrive one question at a
     * time (see [rowsForQuestions]) without ever holding the whole log.
     */
    @Query(
        "SELECT DISTINCT questionId FROM review_log " +
            "WHERE questionId > :afterId ORDER BY questionId LIMIT :limit"
    )
    suspend fun pageQuestionIdsAfter(afterId: String, limit: Int): List<String>

    /**
     * Every row of [questionIds], each question's rows oldest first — the order
     * a per-card training history needs. The `(questionId, reviewedAt)` index
     * serves the ordering; `id` only breaks exact-timestamp ties.
     */
    @Query(
        "SELECT * FROM review_log WHERE questionId IN (:questionIds) " +
            "ORDER BY questionId ASC, reviewedAt ASC, id ASC"
    )
    suspend fun rowsForQuestions(questionIds: Collection<String>): List<ReviewLogEntity>

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

    /**
     * Drops the history of every question in one category, resolved as a
     * subquery so the ids are never listed. Must run before the category (and
     * its cascade) is deleted; the FK here points at the paper, not the
     * question, so the question cascade cannot reach the log.
     */
    @Query(
        "DELETE FROM review_log WHERE questionId IN " +
            "(SELECT id FROM questions WHERE categoryId = :categoryId)"
    )
    suspend fun deleteAllForCategory(categoryId: String): Int
}

@Dao
interface BookmarkDao {
    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAll(): List<String>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun countAll(): Int

    /**
     * One keyset page of bookmark ids, in primary-key order. The export used to
     * read the whole table into a list and then encode that list into a single
     * JSON String — two whole-bank allocations for a payload whose order does
     * not matter (re-import restores a set). Keyset order keeps one page live.
     */
    @Query("SELECT questionId FROM bookmarks WHERE questionId > :afterId ORDER BY questionId LIMIT :limit")
    suspend fun pageIdsAfter(afterId: String, limit: Int): List<String>

    /**
     * How many bookmarks there are, re-read only when the table changes. The
     * bookmarks screen wants the *number* (and a signal that the set changed),
     * never the ids: reading those back to count them would materialise a
     * million strings on every bookmark.
     */
    @Query("SELECT COUNT(*) FROM bookmarks")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE questionId = :questionId")
    suspend fun remove(questionId: String)

    @Query("DELETE FROM bookmarks WHERE questionId IN (:questionIds)")
    suspend fun removeAll(questionIds: Collection<String>)

    /** How many of [questionIds] are bookmarked, for the delete confirmation. */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE questionId IN (:questionIds)")
    suspend fun countForQuestions(questionIds: Collection<String>): Int

    /**
     * The bookmarks whose question belongs to [paperId]. Deleting a paper used
     * to read every question id of the paper and chunk them through
     * `removeAllChunked`; the relation is a join away, so a million-question
     * paper never materialises its ids. Must run before the paper's cascade,
     * while its questions still exist.
     */
    @Query(
        "DELETE FROM bookmarks WHERE questionId IN (" +
            "SELECT questions.id FROM questions INNER JOIN categories " +
            "ON questions.categoryId = categories.id WHERE categories.paperId = :paperId)"
    )
    suspend fun removeAllByPaper(paperId: String)

    /** The count form of [removeAllByPaper], for the delete confirmation. */
    @Query(
        "SELECT COUNT(*) FROM bookmarks WHERE questionId IN (" +
            "SELECT questions.id FROM questions INNER JOIN categories " +
            "ON questions.categoryId = categories.id WHERE categories.paperId = :paperId)"
    )
    suspend fun countByPaper(paperId: String): Int

    /**
     * The same one level down: the bookmarks whose question sits in
     * [categoryId]. Deleting a category cascades only its own questions, so the
     * paper-wide form cannot serve it.
     */
    @Query(
        "DELETE FROM bookmarks WHERE questionId IN (" +
            "SELECT id FROM questions WHERE categoryId = :categoryId)"
    )
    suspend fun removeAllByCategory(categoryId: String)

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

    /**
     * Mistake counts per paper, counted in SQL. Same rule as
     * [getLatestStandings]: a question's most recent graded, answered row
     * decides, and only a wrong one is a mistake. The library badge used to
     * materialise every standing — one object per question ever graded, in
     * every paper — purely to group and size them in Kotlin. This returns at
     * most one row per paper (the bank has ~1000), on the hot path that
     * recomputes the badges after every finished test.
     */
    @Query(
        "SELECT attempts.paperId AS paperId, COUNT(*) AS count " +
            "FROM question_results r " +
            "INNER JOIN attempts ON r.attemptId = attempts.id " +
            "WHERE r.isCorrect = 0 AND r.id IN (" +
            "SELECT MAX(id) FROM question_results " +
            "WHERE selectedOptionIds != '' AND correctOptionIds != '' GROUP BY questionId) " +
            "GROUP BY attempts.paperId"
    )
    suspend fun countMistakesByPaper(): List<PaperMistakeCount>

    /**
     * One paper's ever-missed question ids, most recently missed first — the
     * same standing rule, scoped to the paper in SQL instead of filtering the
     * whole bank's standings in memory. Bounded by how much of this paper was
     * actually missed.
     */
    @Query(
        "SELECT r.questionId AS questionId FROM question_results r " +
            "INNER JOIN attempts ON r.attemptId = attempts.id " +
            "WHERE attempts.paperId = :paperId AND r.isCorrect = 0 AND r.id IN (" +
            "SELECT MAX(id) FROM question_results " +
            "WHERE selectedOptionIds != '' AND correctOptionIds != '' GROUP BY questionId) " +
            "ORDER BY r.id DESC"
    )
    suspend fun getMistakenQuestionIdsForPaper(paperId: String): List<String>

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

    /**
     * Drops one question's lifetime aggregate. `question_stats` has no foreign
     * key to questions (the PK is the question id alone), so this is what every
     * delete site that sweeps `card_state` and `review_log` must call too — the
     * orphan-sweep work stopped at those tables. Unswept, the aggregate row
     * outlives its question: History's "Hardest questions" keeps listing it and
     * every backup carries it.
     */
    @Query("DELETE FROM question_stats WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String): Int

    /**
     * One [getWeakestCategories] bucket per (paper, category): a deleted
     * paper's buckets would otherwise keep counting forever, so the paper's
     * question aggregates go with the paper. Resolves the paper's questions
     * as a subquery and must run inside the deleting transaction, before the
     * paper (and its cascade) is gone.
     */
    @Query(
        "DELETE FROM question_stats WHERE questionId IN " +
            "(SELECT id FROM questions WHERE categoryId IN " +
            "(SELECT id FROM categories WHERE paperId = :paperId))"
    )
    suspend fun deleteAllForPaper(paperId: String): Int

    /**
     * Drops the aggregates of every question in one category, resolved as a
     * subquery so the ids are never listed. Must run before the category (and
     * its cascade) is deleted, for the same reason as
     * [CardStateDao.deleteAllForCategory]: the row keys on the question id
     * alone, so the question cascade cannot reach it.
     */
    @Query(
        "DELETE FROM question_stats WHERE questionId IN " +
            "(SELECT id FROM questions WHERE categoryId = :categoryId)"
    )
    suspend fun deleteAllForCategory(categoryId: String): Int
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
 * One question of a test session as a reader hands it over: the id it is
 * resolved and answered by, and the passage it moves with. It deliberately
 * carries nothing else — a preview or explanation text on each row is tens to
 * hundreds of megabytes of strings that no session code reads. A session's
 * whole order is held as a [com.mcqapp.domain.SessionOrder] now; this is the
 * row shape for the readers that do materialise a list (mistakes, by id).
 */
data class SessionRef(
    val id: String,
    val passageId: String? = null
)

/**
 * One keyset page of a session's rows: the id, the passage it moves with, and
 * the `(sortOrder, rowid)` a caller passes back to read the next page. A
 * session order is built from these without ever holding the refs.
 */
data class SessionRefRow(
    val id: String,
    val passageId: String? = null,
    val sortOrder: Int = 0,
    val rowId: Long = 0
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

/** One row of [AttemptDao.countMistakesByPaper]: a paper and its mistake count. */
data class PaperMistakeCount(
    val paperId: String,
    val count: Int
)

/**
 * One row of [QuestionDao.pageBookmarkedQuestionsForTitle]: a bookmarked
 * question and its bookmark's timestamp. The timestamp is the keyset the
 * bookmark export pages a title group by, so it travels beside the question.
 */
data class BookmarkedQuestionRow(
    @Embedded val question: QuestionEntity,
    val bookmarkCreatedAt: Long
)
