package com.mcqapp.ui.navigation

import android.net.Uri

/**
 * Builds navigation routes with every id percent-encoded.
 *
 * Ids come from imported files and are not validated, so an id containing
 * `&`, `#`, `/` or `?` used to reshape the route: `"id": "x&paperId=other"`
 * opened the editor against a different paper, and a following save wrote the
 * question into it. Navigation decodes query values for us, so encoding at the
 * point of construction is the whole fix.
 *
 * Centralising the routes also keeps a new screen from reintroducing the bug by
 * interpolating an id by hand.
 */
internal fun encode(value: String): String = Uri.encode(value)

fun editorRoute(questionId: String, paperId: String, categoryId: String): String =
    "editor?questionId=${encode(questionId)}" +
        "&paperId=${encode(paperId)}" +
        "&categoryId=${encode(categoryId)}"

fun browseRoute(paperId: String, focusQuestionId: String? = null): String =
    if (focusQuestionId == null) "browse/${encode(paperId)}"
    else "browse/${encode(paperId)}?focus=${encode(focusQuestionId)}"

fun studyRoute(paperId: String, leechesOnly: Boolean = false): String =
    if (leechesOnly) "study/${encode(paperId)}?leechesOnly=true"
    else "study/${encode(paperId)}"

fun resultsRoute(attemptId: Long): String = "results/$attemptId"

fun testRoute(
    paperId: String,
    categoryIds: List<String> = emptyList(),
    mistakes: Boolean = false,
    drillCount: Int = 0,
    drillMinutes: Int = 0
): String = "test?paperId=${encode(paperId)}" +
    "&categories=${encode(categoryIds.joinToString(","))}" +
    "&mistakes=$mistakes" +
    "&drillCount=$drillCount" +
    "&drillMinutes=$drillMinutes"
