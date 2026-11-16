package com.mcqapp.data.io

import com.mcqapp.data.local.CardStateEntity

/**
 * One stored card as the schedule the wire format carries.
 *
 * Three callers need this exact conversion — the streamed whole-bank export,
 * the in-memory DTO builder and the Anki package's per-batch provider — and a
 * field left out of one of them is a review that silently restarts. Whether a
 * row counts as studied is the caller's rule, not this mapper's.
 */
/**
 * Separator of the JSON export's composite schedule key. A NUL cannot occur in
 * a generated id and keeps the key unambiguous, unlike `/` or `:`.
 */
internal const val CARD_SCHEDULE_KEY_SEPARATOR = '\u0000'

/**
 * The plain JSON export's key for one card's schedule: `paperId` NUL
 * `questionId`.
 *
 * The map used to be keyed by question id alone, so two papers holding the
 * same question collapsed into one entry — and the survivor was then applied
 * to every occurrence on import. A version-2 writer also emits the bare
 * question id when that id belongs to exactly one paper in the file, so
 * readers written before version 2 still find the entry; readers resolve the
 * two forms through [lookupCardSchedule].
 */
internal fun cardScheduleKey(paperId: String, questionId: String): String =
    "$paperId$CARD_SCHEDULE_KEY_SEPARATOR$questionId"

/**
 * The `scheduling` keys one card is written under: its composite key, then the
 * bare question id when that id is not in [ambiguousIds].
 *
 * Both writers — [Exporter]'s streamed whole-bank export and its in-memory DTO
 * builder — call this rather than spelling the rule twice, so the two cannot
 * disagree about which keys a card carries: their alias rule drifted apart
 * once already, and a writer that quietly drops the alias costs every
 * pre-version-2 reader the card's review progress. [ambiguousIds] is
 * `CardStateDao.ambiguousScheduleQuestionIds`, read once per export; an id
 * held by two papers gets no alias because an old reader could not tell the
 * two schedules apart.
 */
internal fun scheduleKeysFor(
    paperId: String,
    questionId: String,
    ambiguousIds: Set<String>
): List<String> = if (questionId in ambiguousIds) {
    listOf(cardScheduleKey(paperId, questionId))
} else {
    listOf(cardScheduleKey(paperId, questionId), questionId)
}

/**
 * The entry naming one card in a file's `scheduling` map, resolved with the
 * precedence of the format [version] the file declares.
 *
 * Version 2 and later write the composite key for every card and add the bare
 * question id only as a compatibility alias, so the composite key wins; a
 * version-1 file has bare keys only, so the bare one wins and the composite
 * lookup is the fallback. Both readers — [Importer]'s whole-file restore and
 * [StreamingImportSession]'s tail apply — resolve through here, because two
 * orderings for one format drifted apart once already.
 *
 * The `version >= [JSON_EXPORT_VERSION]` test is the format's contract, not a
 * convenience: every version from 2 onward keeps this key shape, so a version
 * that changes the keys must change this branch — it must never inherit the
 * version-2 precedence silently.
 */
internal fun lookupCardSchedule(
    schedules: Map<String, CardScheduleDto>,
    paperId: String,
    questionId: String,
    version: Int
): CardScheduleDto? {
    val composite = cardScheduleKey(paperId, questionId)
    return if (version >= JSON_EXPORT_VERSION) {
        schedules[composite] ?: schedules[questionId]
    } else {
        schedules[questionId] ?: schedules[composite]
    }
}

internal fun CardStateEntity.toCardScheduleDto(): CardScheduleDto = CardScheduleDto(
    ease = ease,
    intervalDays = intervalDays,
    dueAt = dueAt,
    reps = reps,
    lapses = lapses,
    leech = leech,
    lastReviewedAt = lastReviewedAt,
    difficulty = difficulty,
    stability = stability,
    stabilityFast = stabilityFast,
    cardState = cardState.name,
    reviewCount = reviewCount
)
