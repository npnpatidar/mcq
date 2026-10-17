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

suspend fun CorrectAnswerDao.getQuestionIdsWithKeysChunked(questionIds: List<String>): Set<String> =
    questionIds.chunks().flatMap { getQuestionIdsWithKeys(it) }.toHashSet()

suspend fun QuestionDao.getByIdsChunked(ids: List<String>): List<QuestionEntity> =
    ids.chunks().flatMap { getByIds(it) }

suspend fun QuestionDao.getByCategoriesChunked(categoryIds: List<String>): List<QuestionEntity> =
    categoryIds.chunks().flatMap { getByCategories(it) }

suspend fun QuestionDao.getRefsByCategoriesChunked(categoryIds: List<String>): List<QuestionRef> =
    categoryIds.chunks().flatMap { getRefsByCategories(it) }

suspend fun QuestionDao.getSessionRefsByCategoriesChunked(
    categoryIds: List<String>
): List<SessionRef> =
    categoryIds.chunks().flatMap { getSessionRefsByCategories(it) }

suspend fun QuestionDao.getSessionRefsByIdsChunked(ids: List<String>): List<SessionRef> =
    ids.chunks().flatMap { getSessionRefsByIds(it) }

suspend fun QuestionDao.getHashInputsByCategoriesChunked(
    categoryIds: List<String>
): List<QuestionIdHash> =
    categoryIds.chunks().flatMap { getHashInputsByCategories(it) }

suspend fun QuestionDao.getRefsByIdsChunked(ids: List<String>): List<QuestionRef> =
    ids.chunks().flatMap { getRefsByIds(it) }

suspend fun QuestionDao.getMatchesByContentHashesChunked(
    hashes: Collection<String>
): List<ContentHashMatch> =
    hashes.toList().chunks().flatMap { getMatchesByContentHashes(it) }

suspend fun QuestionDao.getExistingIdsChunked(ids: Collection<String>): List<String> =
    ids.toList().chunks().flatMap { getExistingIds(it) }

suspend fun BookmarkDao.removeAllChunked(questionIds: Collection<String>) {
    questionIds.toList().chunks().forEach { removeAll(it) }
}

suspend fun BookmarkDao.countForQuestionsChunked(questionIds: Collection<String>): Int =
    questionIds.toList().chunks().sumOf { countForQuestions(it) }

suspend fun CategoryDao.getByIdsChunked(ids: List<String>): List<CategoryEntity> =
    ids.chunks().flatMap { getByIds(it) }

suspend fun PassageDao.getByIdsChunked(ids: List<String>): List<PassageEntity> =
    ids.chunks().flatMap { getByIds(it) }

suspend fun PaperDao.getByIdsChunked(ids: List<String>): List<PaperEntity> =
    ids.chunks().flatMap { getByIds(it) }

suspend fun QuestionDao.getIdCategoriesByIdsChunked(ids: List<String>): List<QuestionCategoryRow> =
    ids.chunks().flatMap { getIdCategoriesByIds(it) }

suspend fun QuestionDao.updateCategoryChunked(ids: Collection<String>, categoryId: String): Int =
    ids.toList().chunks().sumOf { updateCategory(it, categoryId) }

suspend fun QuestionDao.updateBulkFieldsChunked(
    ids: Collection<String>,
    marks: Double?,
    difficulty: String?,
    tags: String?
): Int =
    ids.toList().chunks().sumOf { updateBulkFields(it, marks, difficulty, tags) }

suspend fun CardStateDao.deleteByQuestionsChunked(questionIds: Collection<String>) {
    questionIds.toList().chunks().forEach { deleteByQuestions(it) }
}

suspend fun ReviewLogDao.deleteByQuestionsChunked(questionIds: Collection<String>) {
    questionIds.toList().chunks().forEach { deleteByQuestions(it) }
}

/**
 * Escapes [prefix] for a `LIKE :pattern ESCAPE '\\'` query so `%`, `_` and the
 * escape character itself match literally, then appends the wildcard: the
 * query returns exactly the ids that start with [prefix].
 */
fun likePrefixPattern(prefix: String): String =
    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/**
 * Matches every id containing "-copy" — the substring all generated copy ids
 * share (`q1-copy`, `q1-copy-2`, …, via BulkOps.copyId and
 * PaperClone.copyPaperId), so these are the only existing ids a copy candidate
 * can collide with. One scan with this pattern replaces materializing every id
 * in the table; extra `-copy`-shaped ids it returns merely over-block, which
 * the full-table id set it replaces did too.
 */
const val COPY_ID_PROBE = "%-copy%"
