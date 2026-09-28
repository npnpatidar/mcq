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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(paper: PaperEntity)

    @Query("DELETE FROM papers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE paperId = :paperId ORDER BY sortOrder, title")
    suspend fun getByPaper(paperId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE categoryId = :categoryId ORDER BY sortOrder, rowid")
    suspend fun getByCategory(categoryId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions")
    fun observeAll(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: String): QuestionEntity?

    @Query("SELECT * FROM questions WHERE text LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY rowid DESC")
    suspend fun search(query: String): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM questions WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: QuestionEntity)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface OptionDao {
    @Query("SELECT * FROM options WHERE questionId = :questionId ORDER BY sortOrder, rowid")
    suspend fun getByQuestion(questionId: String): List<OptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(options: List<OptionEntity>)

    @Query("DELETE FROM options WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)
}

@Dao
interface CorrectAnswerDao {
    @Query("SELECT optionId FROM correct_answers WHERE questionId = :questionId")
    suspend fun getCorrectIds(questionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(answers: List<CorrectAnswerEntity>)

    @Query("DELETE FROM correct_answers WHERE questionId = :questionId")
    suspend fun deleteByQuestion(questionId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT questionId FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE questionId = :questionId")
    suspend fun remove(questionId: String)

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

    @Query("SELECT * FROM attempts WHERE id = :id")
    suspend fun getById(id: Long): AttemptEntity?

    @Query("SELECT * FROM question_results WHERE attemptId = :attemptId ORDER BY rowid")
    suspend fun getResults(attemptId: Long): List<QuestionResultEntity>

    @Query("DELETE FROM attempts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
