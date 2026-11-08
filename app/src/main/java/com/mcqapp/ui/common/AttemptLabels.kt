package com.mcqapp.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mcqapp.R
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestType

/**
 * The display name of a [TestType] (section 11.5). Shared by the result page
 * and the History row so the two can never name a type differently.
 */
@Composable
fun testTypeLabel(type: TestType?): String = when (type) {
    TestType.RANDOM -> stringResource(R.string.report_type_random)
    TestType.BALANCED -> stringResource(R.string.report_type_balanced)
    TestType.WEAKEST_TOPICS -> stringResource(R.string.report_type_weakest_topics)
    TestType.TOUGHEST_QUESTIONS -> stringResource(R.string.report_type_toughest)
    TestType.EASIEST_QUESTIONS -> stringResource(R.string.report_type_easiest)
    TestType.MISTAKES -> stringResource(R.string.report_type_mistakes)
    TestType.TRICKY -> stringResource(R.string.report_type_tricky)
    TestType.AT_RISK -> stringResource(R.string.report_type_at_risk)
    TestType.UNSEEN -> stringResource(R.string.report_type_unseen)
    TestType.BOOKMARKED -> stringResource(R.string.report_type_bookmarked)
    TestType.TAG -> stringResource(R.string.report_type_tag)
    TestType.AUTHORED_DIFFICULTY -> stringResource(R.string.report_type_difficulty)
    TestType.SLOWEST -> stringResource(R.string.report_type_slowest)
    TestType.MIXED -> stringResource(R.string.report_type_mixed)
    null -> stringResource(R.string.report_legacy_attempt)
}

/**
 * The attempt's type line, with the custom choice appended (phase 3): a Tag
 * run reads `Tag · polity`, an Authored difficulty run reads
 * `Authored difficulty · hard`. Every other type reads its plain label.
 */
@Composable
fun attemptTypeLine(attempt: Attempt): String {
    val type = TestType.fromStorage(attempt.testType)
    val base = testTypeLabel(type)
    val variant = attempt.variant?.trim()?.takeIf { it.isNotEmpty() } ?: return base
    return when (type) {
        TestType.TAG -> stringResource(R.string.report_type_tag_variant, variant)
        TestType.AUTHORED_DIFFICULTY ->
            stringResource(R.string.report_type_difficulty_variant, variant.lowercase())
        else -> base
    }
}

/** The attempt family's short label: Mock, Fresh sample, Practice, Short or Legacy. */
@Composable
fun attemptFamilyLabel(attempt: Attempt): String = familyLabel(TestReportBuilder.familyOf(attempt))

/** [attemptFamilyLabel] for a family value already in hand. */
@Composable
fun familyLabel(family: ReportFamily): String = when (family) {
    ReportFamily.MOCK -> stringResource(R.string.report_mock)
    ReportFamily.FRESH_SAMPLE -> stringResource(R.string.report_fresh_sample)
    ReportFamily.PRACTICE -> stringResource(R.string.report_practice)
    ReportFamily.SHORT -> stringResource(R.string.report_short_test)
    ReportFamily.LEGACY -> stringResource(R.string.report_legacy_attempt)
}
