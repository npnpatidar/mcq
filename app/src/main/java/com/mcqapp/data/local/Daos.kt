package com.mcqapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM papers WHERE title = :title ORDER BY createdAt ASC LIMIT 1")
    suspend fun getByTitle(title: String): PaperEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(paper: PaperEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(paper: PaperEntity)

    @Query("UPDATE papers SET title = :title, description = :description, durationMinutes = :durationMinutes, negativeMarking = :negativeMarking WHERE id = :id")
    suspend fun updateFields(id: String, title: String, description: String, durationMinutes: Int, negativeMarking: Double)

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

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE categoryId = :categoryId ORDER BY sortOrder, rowid")
    suspend fun getByCategory(categoryId: String): List<QuestionEntity>

    @Query(
        "SELECT questions.* FROM questions " +
            "INNER JOIN categories ON questions.categoryId = categories.id " +
            "WHERE questions.categoryId IN (:categoryIds) " +
            "ORDER BY categories.rowid, questions.sortOrder, questions.rowid"
    )
    suspend fun getByCategories(categoryIds: List<String>): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: String): Flow<QuestionEntity?>

    @Query("SELECT * FROM questions")
    fun observeAll(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions")
    suspend fun getAll(): List<QuestionEntity>

    @Query("SELECT categoryId, COUNT(*) as cnt FROM questions GROUP BY categoryId")
    fun observeCategoryCounts(): Flow<List<CategoryCountEntity>>

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

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<QuestionEntity>

    @Query("SELECT MAX(sortOrder) FROM questions WHERE categoryId = :categoryId")
    suspend fun getMaxSortOrder(categoryId: String): Int?

    @Query("UPDATE questions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("SELECT * FROM questions WHERE text LIKE '%' || :query || '%' ESCAPE '\\' OR tags LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY rowid DESC")
    suspend fun search(query: String): List<QuestionEntity>

    @Query(
        "SELECT DISTINCT questions.* FROM questions LEFT JOIN options " +
            "ON options.questionId = questions.id WHERE questions.text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR questions.tags LIKE '%' || :query || '%' ESCAPE '\\' OR options.text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY questions.rowid DESC"
    )
    suspend fun searchIncludingOptions(query: String): List<QuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: QuestionEntity)

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

    @Query("SELECT * FROM card_state WHERE paperId = :paperId AND questionId = :questionId")
    suspend fun get(paperId: String, questionId: String): CardStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: CardStateEntity)

    @Query("DELETE FROM card_state WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)

    /**
     * Drops every SM-2 card for a paper. The foreign key already cascades, but
     * deleting explicitly keeps the intent visible and does not depend on the
     * foreign-key pragma being enabled.
     */
    @Query("DELETE FROM card_state WHERE paperId = :paperId")
    suspend fun deleteByPaper(paperId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAll(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE questionId = :questionId")
    suspend fun remove(questionId: String)

    @Query("DELETE FROM bookmarks WHERE questionId IN (:questionIds)")
    suspend fun removeAll(questionIds: Collection<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE questionId = :questionId)")
    suspend fun isBookmarked(questionId: String): Boolean
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Insert
    suspend fun insertResults(results: List<QuestionResultEntity>)

    @Query("SELECT * FROM attempts ORDER BY finishedAt DESC")
    fun observeAll(): Flow<List<AttemptEntity>>

    /** Attempts of one paper only, instead of filtering the whole table in Kotlin. */
    @Query("SELECT * FROM attempts WHERE paperId = :paperId ORDER BY finishedAt ASC")
    suspend fun getByPaper(paperId: String): List<AttemptEntity>

    /**
     * Graded results for one paper's questions, filtered in SQL. The badge read
     * used to pull every row of `question_results` and every attempt into
     * memory and discard most of them in Kotlin.
     */
    @Query(
        "SELECT question_results.* FROM question_results " +
            "INNER JOIN attempts ON question_results.attemptId = attempts.id " +
            "WHERE attempts.paperId = :paperId " +
            "AND question_results.questionId IN (:questionIds) " +
            "AND question_results.selectedOptionIds != '' " +
            "AND question_results.correctOptionIds != ''"
    )
    suspend fun getGradedResultsForQuestions(
        paperId: String,
        questionIds: List<String>
    ): List<QuestionResultEntity>

    @Query("SELECT * FROM attempts WHERE id = :id")
    suspend fun getById(id: Long): AttemptEntity?

    @Query("SELECT * FROM question_results WHERE attemptId = :attemptId ORDER BY rowid")
    suspend fun getResults(attemptId: Long): List<QuestionResultEntity>

    @Query("SELECT * FROM question_results ORDER BY rowid")
    suspend fun getAllResults(): List<QuestionResultEntity>

    @Query("SELECT * FROM attempts ORDER BY finishedAt ASC")
    suspend fun getAllAttempts(): List<AttemptEntity>

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
