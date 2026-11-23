package com.mcqapp.data.io

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity

/**
 * The decisions the two import paths share, as pure functions.
 *
 * `Importer` (whole file) and `StreamingImportSession` (per batch) used to
 * spell out the same four rules side by side: which stored row a hash hit
 * really names, how a foreign id is remapped for this paper, whether content
 * changed enough to reset a card's schedule, and which key names a restored
 * schedule. They drifted before (a table-only difference read as a duplicate;
 * a same-paper id was remapped twice), and only the Robolectric equivalence
 * suite could catch it — not a JVM test, not a compile error.
 *
 * Everything here is data in / decision out: no database, no coroutine, no
 * Android framework call. The importers still do the IO (read candidates,
 * write rows); they ask this object only "what should I do about this row?".
 * `ImportDecisionTest` pins the rules on the JVM; `StreamingImporterEquivalenceTest`
 * stays as the end-to-end safety net for the wiring.
 */
internal object ImportDecision {

    /**
     * A stored question that shares a content hash, with everything needed to
     * tell whether it is really the incoming question and whether its stored
     * answer key needs refreshing. Owned here so both importers pass the same
     * shape through the same decisions.
     */
    data class StoredCandidate(
        val entity: QuestionEntity,
        val options: List<OptionEntity>,
        val correctIds: Set<String>
    )

    // --- candidate matching ------------------------------------------------

    /**
     * The stored row [contentHash] really names, or null when the hash is a
     * collision. The hash cannot see tables, formulas or images, so a hash hit
     * is only a candidate: [ContentHash.sameQuestionContent] is the authority.
     * The first match wins, exactly as the old per-importer `firstOrNull` did.
     * Questions written earlier in this same import are handled by
     * [isSameContent] below (there is no stored row to refresh).
     */
    fun candidateFor(
        contentHash: String,
        incoming: QuestionDto,
        candidates: Map<String, List<StoredCandidate>>
    ): StoredCandidate? = candidates[contentHash].orEmpty().firstOrNull {
        ContentHash.sameQuestionContent(it.entity, it.options, incoming)
    }
}

/**
 * Whether any stored row — or any question written earlier in this same
 * import — really is [incoming]. Used by the "duplicate" decision; the hash
 * is only the cheap pre-filter.
 */
internal fun ImportDecision.isSameContent(
    contentHash: String,
    incoming: QuestionDto,
    candidates: Map<String, List<ImportDecision.StoredCandidate>>,
    writtenThisImport: Map<String, List<QuestionDto>> = emptyMap()
): Boolean =
    writtenThisImport[contentHash].orEmpty().any { ContentHash.sameQuestionContent(it, incoming) } ||
        candidates[contentHash].orEmpty().any {
            ContentHash.sameQuestionContent(it.entity, it.options, incoming)
        }

/** A stored row whose non-hashed fields (answer key, back, marks, …) changed. */
internal fun ImportDecision.needsAnswerRefresh(
    stored: ImportDecision.StoredCandidate,
    incoming: QuestionDto
): Boolean = ContentHash.nonHashedFieldsDiffer(
    stored.entity, stored.options, stored.correctIds, incoming
)

/** A refreshed key or image invalidates the schedule the old content earned. */
internal fun ImportDecision.refreshInvalidatesSchedule(
    stored: ImportDecision.StoredCandidate,
    incoming: QuestionDto
): Boolean = CardContent.of(stored.entity, stored.options, stored.correctIds) != CardContent.of(incoming)

// --- remapping foreign ids --------------------------------------------------

/**
 * The id a question is written under. An id owned by another paper is remapped
 * so REPLACE cannot move that row into this paper (consolidated_bugs.md A2).
 * A row this import already wrote ([adoptId]) keeps its id when it belongs to
 * this paper — including an id remapped on an earlier batch of the same file.
 */
internal fun ImportDecision.questionIdForWrite(
    rawId: String,
    ownerPaperId: String?,
    effectivePaperId: String,
    adoptId: String? = null
): String = when {
    ownerPaperId != null && ownerPaperId != effectivePaperId -> "$effectivePaperId-$rawId"
    adoptId != null -> adoptId
    else -> rawId
}

/**
 * The category id written for this paper. A title/id match in the destination
 * paper wins; otherwise an id owned elsewhere is remapped (REPLACE on a
 * cross-paper category would CASCADE-delete its questions).
 */
internal fun ImportDecision.categoryIdForWrite(
    rawId: String,
    ownerPaperId: String?,
    effectivePaperId: String,
    matchInPaperId: String? = null
): String = matchInPaperId ?: if (ownerPaperId != null && ownerPaperId != effectivePaperId) {
    "$effectivePaperId-$rawId"
} else {
    rawId
}

/** The passage id written for this paper (consolidated_bugs.md A3). */
internal fun ImportDecision.passageIdForWrite(
    rawId: String,
    ownerPaperId: String?,
    effectivePaperId: String
): String = if (ownerPaperId != null && ownerPaperId != effectivePaperId) {
    "$effectivePaperId-$rawId"
} else {
    rawId
}

// --- schedule decisions ----------------------------------------------------

/**
 * Whether the content a card was learned from changed, which is what triggers
 * a schedule reset. A different hash is decisive without reading the stored
 * options back; a matching hash may still hide a table, formula, key or image,
 * so only that case pays for the [sameHashContent] comparison (kept lazy so a
 * caller never fetches rows it does not need).
 */
internal suspend fun ImportDecision.contentChanged(
    existing: QuestionEntity?,
    ownedElsewhere: Boolean,
    hashMatches: Boolean,
    incoming: QuestionDto,
    sameHashContent: suspend () -> Boolean
): Boolean = existing != null && !ownedElsewhere && (!hashMatches || sameHashContent())

/**
 * The single key-precedence rule for a restored schedule, reached from both
 * importers through this seam. Delegates to the format-aware helper so a
 * version bump still has exactly one place to change.
 */
internal fun ImportDecision.scheduleFor(
    schedules: Map<String, CardScheduleDto>,
    sourcePaperId: String,
    sourceQuestionId: String,
    version: Int
): CardScheduleDto? = lookupCardSchedule(schedules, sourcePaperId, sourceQuestionId, version)

// --- paper-level decisions --------------------------------------------------

/**
 * A brand-new paper whose incoming questions all already exist writes no
 * categories and no questions — only an empty shell. Treat that as "nothing
 * was added": drop the shell and do not count it as a new paper. A paper with
 * zero questions is NOT this case (the skip needs a non-empty set), nor is one
 * where a duplicate's answer key was refreshed.
 */
internal fun ImportDecision.allDuplicatesPaperSkip(
    isNewPaper: Boolean,
    needsWrite: Boolean,
    sawAnyQuestion: Boolean
): Boolean = isNewPaper && !needsWrite && sawAnyQuestion

/** Paper counts for a finished paper; a skipped shell is neither new nor updated. */
internal fun ImportDecision.countPaper(isNewPaper: Boolean, skipped: Boolean): Pair<Int, Int> = when {
    skipped -> 0 to 0
    isNewPaper -> 1 to 0
    else -> 0 to 1
}
