package com.mcqapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One collapsible Settings card: a chevron + label header row and the group
 * content when expanded. The header is a toggleable Role.Button so TalkBack
 * exposes the expanded state without a stateDescription string; the same
 * chevron pattern the Advanced Anki block used is reused for every section.
 */
@Composable
fun CollapsibleSettingsSection(
    @androidx.annotation.StringRes titleRes: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = expanded,
                        role = Role.Button,
                        onValueChange = onExpandedChange
                    )
                    .testTag(testTag)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown
                    else Icons.Default.KeyboardArrowRight,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
            if (expanded) {
                content()
            }
        }
    }
}

/**
 * One switch row: the whole row is selectable with Role.Switch so the touch
 * target is the full row, not just the thumb. The label/help text is the
 * announcement, and the Switch mirrors it for the same reason the radio rows
 * keep a RadioButton inside their selectable row.
 */
@Composable
fun SwitchSettingRow(
    label: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    var rowModifier = modifier
        .fillMaxWidth()
        .selectable(
            selected = checked,
            onClick = { onChange(!checked) },
            role = Role.Switch
        )
        .padding(vertical = 4.dp)
    if (testTag != null) {
        rowModifier = rowModifier.testTag(testTag)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
