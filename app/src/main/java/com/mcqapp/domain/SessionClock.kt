package com.mcqapp.domain

/**
 * Whether a reading clock may run now.
 *
 * Study charges question time and the test screen charges dwell, but the rule
 * they ask is the same: the card has to be on screen, the learner must not have
 * paused it deliberately, and there has to be an answerable card. Visibility is
 * part of the gate rather than a separate check at each resume path, so
 * returning from the background cannot restart a clock for a question that is
 * scrolled out of the viewport or still covered by a dialog - the visibility
 * effect that paused it does not re-fire on resume, because its key did not
 * change.
 */
internal fun clockMayRun(
    questionInView: Boolean,
    pausedByUser: Boolean,
    answerable: Boolean
): Boolean = questionInView && !pausedByUser && answerable
