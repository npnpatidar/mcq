package com.mcqapp.domain

/**
 * The one saved test session, as the Library and History cards present it:
 * the header rebuilt with its answer rows joined in, plus the count of
 * questions actually answered (reaching a question is not answering it).
 *
 * This is a read model only. The resume slot still lives in
 * [TestSnapshot] / `test_progress`; nothing here is persisted.
 */
data class UnfinishedTest(
    val snapshot: TestSnapshot,
    val answeredCount: Int
) {
    val paperId: String get() = snapshot.paperId
}
