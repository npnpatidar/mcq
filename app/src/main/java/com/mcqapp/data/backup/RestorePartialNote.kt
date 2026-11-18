package com.mcqapp.data.backup

import com.mcqapp.R

/**
 * A caller-owned preference write that failed *after* the library restore
 * committed. Carries a resource id, never exception detail: the throwable goes
 * to [com.mcqapp.util.Logger] only, and the learner sees a generic localized
 * phrase built from this enum (SE3 / report 07).
 */
enum class RestorePartial(val resId: Int) {
    SETTINGS(R.string.r2_backup_partial_settings),
    IN_PROGRESS(R.string.r2_backup_partial_in_progress)
}

/**
 * Builds the "partial restore" copy without ever looking at a throwable's
 * message. Keeping this in one small object makes the privacy guarantee easy to
 * test on a plain JVM and lets the UI supply a localized resolver later without
 * changing the restore transaction.
 */
object RestorePartialCopy {

    /** Detail-free fallback for callers with no resource resolver. */
    fun fallback(kind: RestorePartial): String = when (kind) {
        RestorePartial.SETTINGS -> "settings were not restored"
        RestorePartial.IN_PROGRESS -> "the in-progress test was not restored"
    }

    /** The whole sentence from generic phrases, no exception detail. */
    fun fallback(items: List<RestorePartial>): String =
        "The library itself came back; " + items.joinToString(" and ") { fallback(it) } + "."

    /**
     * Localized renderer: every piece of the sentence comes through the
     * caller's resource lookup, so both the prefix and each item follow the
     * app's selected language.
     */
    fun localized(items: List<RestorePartial>, getString: (Int) -> String): String =
        // The separator lives here, not in the resource: a translatable string
        // must not carry trailing whitespace, but the rendered sentence still
        // needs a space after the semicolon.
        getString(R.string.r2_backup_partial_prefix) + " " +
            items.joinToString(" and ") { getString(it.resId) } + "."

    /**
     * Logs the failure detail and returns only its stable kind. The [failure]
     * is never rendered into the returned value — that is the point of SE3.
     */
    fun flagged(
        kind: RestorePartial,
        failure: Throwable,
        logDetail: (Throwable) -> Unit
    ): RestorePartial {
        logDetail(failure)
        return kind
    }
}
