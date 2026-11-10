package com.mcqapp.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R

@Composable
fun TestsSection(
    shuffleQuestions: Boolean,
    shuffleOptions: Boolean,
    practiceMode: Boolean,
    strictMode: Boolean,
    autoAdvance: Boolean,
    avoidRecentQuestions: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShuffleQuestionsChange: (Boolean) -> Unit,
    onShuffleOptionsChange: (Boolean) -> Unit,
    onPracticeModeChange: (Boolean) -> Unit,
    onStrictModeChange: (Boolean) -> Unit,
    onAutoAdvanceChange: (Boolean) -> Unit,
    onAvoidRecentQuestionsChange: (Boolean) -> Unit
) {
    CollapsibleSettingsSection(
        titleRes = R.string.settings_section_tests,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        testTag = "settings-section-tests"
    ) {
        SwitchSettingRow(
            label = stringResource(R.string.shuffle_question_order),
            help = stringResource(R.string.present_questions_in_random_order_each_attempt),
            checked = shuffleQuestions,
            onChange = onShuffleQuestionsChange,
            testTag = "setting-shuffle-questions"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.shuffle_options),
            help = stringResource(R.string.present_answer_options_in_random_order),
            checked = shuffleOptions,
            onChange = onShuffleOptionsChange,
            testTag = "setting-shuffle-options"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.practice_mode),
            help = stringResource(R.string.show_correct_answers_and_explanations_instantly),
            checked = practiceMode,
            onChange = onPracticeModeChange,
            testTag = "setting-practice-mode"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.strict_exam_mode),
            help = stringResource(R.string.hides_answers_flags_and_the_question_palette),
            checked = strictMode,
            onChange = onStrictModeChange,
            testTag = "setting-strict-mode"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.auto_advance),
            help = stringResource(R.string.move_to_the_next_question_after_answering_single_answer_only),
            checked = autoAdvance,
            onChange = onAutoAdvanceChange,
            testTag = "setting-auto-advance"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.avoid_recent_questions),
            help = stringResource(R.string.avoid_recent_questions_help),
            checked = avoidRecentQuestions,
            onChange = onAvoidRecentQuestionsChange,
            testTag = "setting-avoid-recent"
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.review_order_help),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
