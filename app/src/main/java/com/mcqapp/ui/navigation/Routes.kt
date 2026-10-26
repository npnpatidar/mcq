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

/**
 * The comma-joined category list, each id encoded twice: Navigation decodes
 * the query argument once, so a single encoding would hand a comma inside an
 * id back as a separator — one id `"a,b"` became two ids, possibly matching
 * other real categories (consolidated_bugs.md D12).
 */
internal fun encodeCategoryList(ids: List<String>): String =
    ids.joinToString(",") { encode(encode(it)) }

/** The inverse of [encodeCategoryList] after Navigation's own decode. */
internal fun decodeCategoryList(raw: String): List<String> =
    raw.split(",").filter { it.isNotBlank() }.map { Uri.decode(it) }

fun editorRoute(questionId: String, paperId: String, categoryId: String): String =
    "editor?questionId=${encode(questionId)}" +
        "&paperId=${encode(paperId)}" +
        "&categoryId=${encode(categoryId)}"

/**
 * Browse's route. [categoryIds] seed the screen's category filter (the ticked
 * branches, not the expanded scope: the destination re-expands them from the
 * paper it already loads), and are appended only when there are any, so every
 * pre-scope caller keeps its URL shape.
 */
fun browseRoute(
    paperId: String,
    focusQuestionId: String? = null,
    categoryIds: List<String> = emptyList()
): String {
    val params = buildList {
        if (focusQuestionId != null) add("focus=${encode(focusQuestionId)}")
        if (categoryIds.isNotEmpty()) add("categories=${encodeCategoryList(categoryIds)}")
    }
    return "browse/${encode(paperId)}" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
}

/**
 * [categoryIds] are the **ticked** ids, not the expanded scope: the header
 * JSON is rewritten on every answer, and an expanded scope would cost a write
 * per category per tap. The destination re-expands them from the paper it
 * already loads (§6.4).
 */
fun studyRoute(
    paperId: String,
    categoryIds: List<String> = emptyList(),
    leechesOnly: Boolean = false
): String {
    val query = buildList {
        if (categoryIds.isNotEmpty()) add("categories=${encodeCategoryList(categoryIds)}")
        if (leechesOnly) add("leechesOnly=true")
    }
    return "study/${encode(paperId)}" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}

fun resultsRoute(attemptId: Long): String = "results/$attemptId"

fun testRoute(
    paperId: String,
    categoryIds: List<String> = emptyList(),
    mistakes: Boolean = false,
    drillCount: Int = 0,
    drillMinutes: Int = 0,
    /** Per-attempt duration in minutes; -1 = the paper's own duration. */
    minutes: Int = com.mcqapp.domain.ExamTiming.USE_PAPER_DURATION
): String = "test?paperId=${encode(paperId)}" +
    "&categories=${encodeCategoryList(categoryIds)}" +
    "&mistakes=$mistakes" +
    "&drillCount=$drillCount" +
    "&drillMinutes=$drillMinutes" +
    "&minutes=$minutes"
