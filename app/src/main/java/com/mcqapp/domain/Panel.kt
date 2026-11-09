package com.mcqapp.domain

/**
 * A fixed panel: a named, stable set of question ids on one paper.
 *
 * The ids are frozen when the panel is created, so repeating it measures the
 * same questions instead of a fresh random sample. That is the point: a mock's
 * score moves partly because the sample moved, while a panel's score moves only
 * because the answers did. A deleted question leaves the panel smaller rather
 * than silently substituting a new one.
 */
data class Panel(
    val id: Long,
    val paperId: String,
    val name: String,
    val questionIds: List<String>
) {
    val size: Int get() = questionIds.size
}

/**
 * A panel without its ids, for list views and progress cards: the size comes
 * from a count, so showing ten panels never reads every member id.
 */
data class PanelSummary(
    val id: Long,
    val paperId: String,
    val name: String,
    val size: Int
)
