package com.mcqapp.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.StorageInfo

@Composable
fun BackupDataSection(
    storage: StorageInfo.Report?,
    updateAnswersOnDuplicate: Boolean,
    pdfTwoColumn: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onUpdateAnswersChange: (Boolean) -> Unit,
    onPdfTwoColumnChange: (Boolean) -> Unit,
    onRefreshStorage: () -> Unit,
    onExportClick: () -> Unit,
    onBackupClick: () -> Unit,
    onShareAllClick: () -> Unit,
    onShareBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onExportLogsError: (Int, String) -> Unit
) {
    var showAllPapers by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    CollapsibleSettingsSection(
        titleRes = R.string.settings_section_backup_data,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        testTag = "settings-section-backup-data"
    ) {
        Text(stringResource(R.string.data), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onExportClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.export_all_data_json))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onShareAllClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.share_all_data))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onBackupClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.backup_full))
        }
        Text(
            stringResource(R.string.backup_full_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onShareBackupClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.share_backup))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRestoreClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.restore_backup))
        }
        Text(
            stringResource(R.string.restore_backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                // Both failure modes used to be silent (no log
                // file) or fatal (no chooser activity)
                // (consolidated_bugs.md H9).
                val path = com.mcqapp.util.Logger.logFilePath()
                if (path == null) {
                    onExportLogsError(R.string.export_logs, "No log file exists yet.")
                    return@Button
                }
                val file = java.io.File(path)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "Export Logs")
                    )
                } catch (e: ActivityNotFoundException) {
                    onExportLogsError(R.string.export_logs, "No app can handle this share.")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.export_logs))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.export_all_help),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.storage),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefreshStorage) {
                Text(stringResource(R.string.refresh))
            }
        }
        Spacer(Modifier.height(8.dp))
        val report = storage
        if (report == null) {
            Text(stringResource(R.string.measuring), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(
                    R.string.storage_database_summary,
                    com.mcqapp.domain.StorageInfo.formatBytes(report.dbBytes),
                    report.papers,
                    report.questions,
                    report.attempts,
                    report.bookmarks
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            storageVisiblePapers(report.perPaper, showAllPapers).forEach { usage ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        usage.title,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        stringResource(
                            R.string.storage_paper_usage,
                            usage.questions,
                            com.mcqapp.domain.StorageInfo.formatBytes(usage.imageChars)
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (shouldShowStorageExpandToggle(report.perPaper.size)) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAllPapers = !showAllPapers }) {
                    Text(
                        if (showAllPapers) {
                            stringResource(R.string.storage_show_fewer)
                        } else {
                            stringResource(R.string.storage_show_all_papers, report.perPaper.size)
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.image_weight_help),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.pdf_export), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.two_column_layout),
            help = stringResource(R.string.flow_questions_down_the_left_column_then_the_right),
            checked = pdfTwoColumn,
            onChange = onPdfTwoColumnChange,
            testTag = "setting-pdf-two-column"
        )
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.refresh_answers_on_re_import),
            help = stringResource(R.string.update_answers_help),
            checked = updateAnswersOnDuplicate,
            onChange = onUpdateAnswersChange,
            testTag = "setting-update-answers"
        )
    }
}
