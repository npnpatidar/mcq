package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mcqapp.R

/**
 * The follow-up actions a finished import or restore offers. One composable so
 * the Library Anki report, the direct-import report and the backup-restore
 * report all present the same labels and the same test tags; a caller supplies
 * only how each action is performed on its own screen.
 */
internal enum class CompletionAction { STUDY, TEST, OPEN_PAPER, OPEN_LIBRARY }

/**
 * The action set, decided purely so the "no identifiable paper" case is pinned
 * by a JVM test. A paper we can name gets the three study actions; one we
 * cannot (an old or empty backup) gets only Open library rather than actions
 * that would navigate to a paper id we do not have.
 */
internal fun completionActionsFor(paperId: String?): List<CompletionAction> =
    if (paperId == null) {
        listOf(CompletionAction.OPEN_LIBRARY)
    } else {
        listOf(CompletionAction.STUDY, CompletionAction.TEST, CompletionAction.OPEN_PAPER)
    }

/** Test tags are shared with the Wave 1 Library dialog so UI tests keep matching. */
internal fun CompletionAction.testTag(): String = when (this) {
    CompletionAction.STUDY -> "import-study-now"
    CompletionAction.TEST -> "import-take-test"
    CompletionAction.OPEN_PAPER -> "import-open-paper"
    CompletionAction.OPEN_LIBRARY -> "import-open-library"
}

internal fun CompletionAction.labelRes(): Int = when (this) {
    CompletionAction.STUDY -> R.string.of_study_now
    CompletionAction.TEST -> R.string.of_take_test
    CompletionAction.OPEN_PAPER -> R.string.of_open_paper
    CompletionAction.OPEN_LIBRARY -> R.string.wg_open_library
}

/**
 * Renders [completionActionsFor] as buttons in a dialog's `dismissButton` slot.
 * The caller still owns dismissing its dialog inside each callback; keeping
 * that here avoids every call site repeating the dismiss-then-navigate order.
 */
@Composable
internal fun CompletionActions(
    paperId: String?,
    onStudy: (String) -> Unit,
    onTest: (String) -> Unit,
    onOpenPaper: (String) -> Unit,
    onOpenLibrary: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        completionActionsFor(paperId).forEach { action ->
            TextButton(
                onClick = {
                    when (action) {
                        CompletionAction.STUDY -> onStudy(requireNotNull(paperId))
                        CompletionAction.TEST -> onTest(requireNotNull(paperId))
                        CompletionAction.OPEN_PAPER -> onOpenPaper(requireNotNull(paperId))
                        CompletionAction.OPEN_LIBRARY -> onOpenLibrary()
                    }
                },
                modifier = Modifier.testTag(action.testTag())
            ) {
                Text(stringResource(action.labelRes()))
            }
        }
    }
}
