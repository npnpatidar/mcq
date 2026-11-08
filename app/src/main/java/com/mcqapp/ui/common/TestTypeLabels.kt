package com.mcqapp.ui.common

import androidx.annotation.StringRes
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
    else -> R.string.test_setup_type_random
}
