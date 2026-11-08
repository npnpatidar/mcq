package com.mcqapp.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mcqapp.R
import com.mcqapp.domain.TestType

/**
 * The display label for a bounded test type. One map for the setup sheet's
 * chips and the session header, so the two can never name a type differently.
 */
@StringRes
fun testTypeLabelRes(type: TestType?): Int = when (type) {
    TestType.MISTAKES -> R.string.test_setup_type_mistakes
    TestType.WEAKEST_TOPICS -> R.string.test_setup_type_weakest
    TestType.TOUGHEST_QUESTIONS -> R.string.test_setup_type_toughest
    TestType.EASIEST_QUESTIONS -> R.string.test_setup_type_easiest
    TestType.BALANCED -> R.string.test_setup_type_balanced
    TestType.TRICKY -> R.string.test_setup_type_tricky
    TestType.AT_RISK -> R.string.test_setup_type_at_risk
    TestType.UNSEEN -> R.string.test_setup_type_unseen
    TestType.MIXED -> R.string.test_setup_type_mixed
    TestType.BOOKMARKED -> R.string.test_setup_type_bookmarked
    TestType.TAG -> R.string.test_setup_type_tag
    TestType.AUTHORED_DIFFICULTY -> R.string.test_setup_type_authored
    TestType.SLOWEST -> R.string.test_setup_type_slowest
    else -> R.string.test_setup_type_random
}

/**
 * The same label for sinks that take a String (a `buildString` summary). Each
 * branch calls `stringResource` directly so the label stays configuration-aware
 * and the string-extraction guard can see where it is rendered.
 */
@Composable
fun testTypeLabelText(type: TestType?): String = when (type) {
    TestType.MISTAKES -> stringResource(R.string.test_setup_type_mistakes)
    TestType.WEAKEST_TOPICS -> stringResource(R.string.test_setup_type_weakest)
    TestType.TOUGHEST_QUESTIONS -> stringResource(R.string.test_setup_type_toughest)
    TestType.EASIEST_QUESTIONS -> stringResource(R.string.test_setup_type_easiest)
    TestType.BALANCED -> stringResource(R.string.test_setup_type_balanced)
    TestType.TRICKY -> stringResource(R.string.test_setup_type_tricky)
    TestType.AT_RISK -> stringResource(R.string.test_setup_type_at_risk)
    TestType.UNSEEN -> stringResource(R.string.test_setup_type_unseen)
    TestType.MIXED -> stringResource(R.string.test_setup_type_mixed)
    TestType.BOOKMARKED -> stringResource(R.string.test_setup_type_bookmarked)
    TestType.TAG -> stringResource(R.string.test_setup_type_tag)
    TestType.AUTHORED_DIFFICULTY -> stringResource(R.string.test_setup_type_authored)
    TestType.SLOWEST -> stringResource(R.string.test_setup_type_slowest)
    else -> stringResource(R.string.test_setup_type_random)
}
