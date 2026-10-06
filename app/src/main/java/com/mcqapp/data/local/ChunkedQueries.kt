package com.mcqapp.data.local

/**
 * SQLite caps the number of bind variables in one statement at 999 on the
 * versions shipped up to API 30 (it only rose to 32766 later), and this app
 * supports API 26. The codebase explicitly handles 25k-question files, so an
 * `IN (:ids)` query with more ids than the limit fails with "too many SQL
 * variables" on older devices.
 *
 * These wrappers chunk the id list and concatenate the results. The DAO methods
 * stay as they are; callers use these instead so no query can grow unbounded.
 */
private const val MAX_BIND_VARS = 500

private fun <T> List<T>.chunks(): List<List<T>> =
    if (isEmpty()) emptyList() else chunked(MAX_BIND_VARS)

suspend fun OptionDao.getForQuestionsChunked(questionIds: List<String>): List<OptionEntity> =
    questionIds.chunks().flatMap { getForQuestions(it) }

suspend fun CorrectAnswerDao.getForQuestionsChunked(questionIds: List<String>): List<CorrectAnswerEntity> =
    questionIds.chunks().flatMap { getForQuestions(it) }

suspend fun QuestionDao.getByIdsChunked(ids: List<String>): List<QuestionEntity> =
    ids.chunks().flatMap { getByIds(it) }

suspend fun QuestionDao.getByCategoriesChunked(categoryIds: List<String>): List<QuestionEntity> =
    categoryIds.chunks().flatMap { getByCategories(it) }

suspend fun AttemptDao.getGradedResultsForQuestionsChunked(
    paperId: String,
    questionIds: List<String>
): List<QuestionResultEntity> =
    questionIds.chunks().flatMap { getGradedResultsForQuestions(paperId, it) }

suspend fun QuestionDao.getMatchesByContentHashesChunked(
    hashes: Collection<String>
): List<ContentHashMatch> =
    hashes.toList().chunks().flatMap { getMatchesByContentHashes(it) }

suspend fun BookmarkDao.removeAllChunked(questionIds: Collection<String>) {
    questionIds.toList().chunks().forEach { removeAll(it) }
}

suspend fun BookmarkDao.countForQuestionsChunked(questionIds: Collection<String>): Int =
    questionIds.toList().chunks().sumOf { countForQuestions(it) }
