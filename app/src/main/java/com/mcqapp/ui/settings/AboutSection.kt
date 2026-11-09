package com.mcqapp.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.BuildConfig
import com.mcqapp.R

@Composable
fun AboutSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    CollapsibleSettingsSection(
        titleRes = R.string.settings_section_about,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        testTag = "settings-section-about"
    ) {
        Text(
            stringResource(R.string.app_about_help),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        // From BuildConfig, not a literal: the version used to be
        // hardcoded here and went stale the moment the build's
        // versionName changed.
        Text(
            stringResource(R.string.version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelMedium
        )
    }
}
