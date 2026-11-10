package com.mcqapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.ui.theme.AppTheme
import com.mcqapp.ui.theme.CustomTheme
import com.mcqapp.ui.theme.isDarkMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    themeMode: String,
    themePalette: String,
    customThemes: List<CustomTheme>,
    fontScale: Float,
    loadRemoteImages: Boolean,
    hapticsEnabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onThemePaletteChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLoadRemoteImagesChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onImportTheme: () -> Unit,
    onRemoveTheme: (String) -> Unit
) {
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var themePendingRemoval by remember { mutableStateOf<CustomTheme?>(null) }
    val feedback = LocalHapticFeedback.current

    themePendingRemoval?.let { theme ->
        AlertDialog(
            onDismissRequest = { themePendingRemoval = null },
            title = { Text(stringResource(R.string.remove_imported_theme)) },
            text = { Text(theme.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveTheme(theme.key)
                        themePendingRemoval = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { themePendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    CollapsibleSettingsSection(
        titleRes = R.string.settings_section_appearance,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        testTag = "settings-section-appearance"
    ) {
        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        listOf(
            "system" to R.string.theme_system_default,
            "light" to R.string.theme_light,
            "dark" to R.string.theme_dark
        ).forEach { (value, labelRes) ->
            val label = stringResource(labelRes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = themeMode == value,
                        onClick = { onThemeModeChange(value) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = themeMode == value,
                    onClick = { onThemeModeChange(value) }
                )
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.color_theme),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(8.dp))
        // The actual mode, so the swatches match what the app
        // will paint right now instead of always showing dark.
        val darkPreview = isDarkMode(themeMode)
        val selectedCustom = customThemes.firstOrNull { it.key == themePalette }
        val selectedBuiltIn = AppTheme.fromKey(themePalette)
        val selectedName = selectedCustom?.name
            ?: stringResource(selectedBuiltIn.labelRes)
        val selectedColors = if (selectedCustom != null) {
            selectedCustom.previewColors(darkPreview)
        } else {
            selectedBuiltIn.previewColors(darkPreview)
        }
        ExposedDropdownMenuBox(
            expanded = themeMenuExpanded,
            onExpandedChange = { themeMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.color_theme)) },
                leadingIcon = { ThemeSwatches(selectedColors) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = themeMenuExpanded
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme-picker")
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = themeMenuExpanded,
                onDismissRequest = { themeMenuExpanded = false }
            ) {
                AppTheme.entries.forEach { theme ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(theme.labelRes))
                                Text(
                                    stringResource(theme.summaryRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            ThemeSwatches(theme.previewColors(darkPreview))
                        },
                        trailingIcon = {
                            if (theme.key == themePalette) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null
                                )
                            }
                        },
                        onClick = {
                            onThemePaletteChange(theme.key)
                            themeMenuExpanded = false
                        }
                    )
                }
                customThemes.forEach { theme ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(theme.name)
                                Text(
                                    stringResource(R.string.imported_theme),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            ThemeSwatches(theme.previewColors(darkPreview))
                        },
                        trailingIcon = {
                            if (theme.key == themePalette) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null
                                )
                            }
                        },
                        onClick = {
                            onThemePaletteChange(theme.key)
                            themeMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                if (selectedCustom != null) R.string.imported_theme
                else selectedBuiltIn.summaryRes
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onImportTheme,
            modifier = Modifier.testTag("theme-import")
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.import_theme))
        }
        if (selectedCustom != null) {
            TextButton(onClick = { themePendingRemoval = selectedCustom }) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.remove_imported_theme))
            }
        }
        Text(
            stringResource(R.string.theme_import_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.text_size), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        com.mcqapp.util.FontScale.OPTIONS.forEach { (scale, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = fontScale == scale,
                        onClick = { onFontScaleChange(scale) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = fontScale == scale,
                    onClick = { onFontScaleChange(scale) }
                )
                // Preview of the chosen size, independent of the
                // ambient scale: McqNavHost overrides LocalDensity
                // with it, so an sp value multiplied by it again
                // rendered the labels at scale squared. Going via dp
                // cancels the ambient scale out first.
                val baseSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                    MaterialTheme.typography.bodyLarge.fontSize.value.dp.toSp()
                }
                Text(
                    label,
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = baseSize * (scale / com.mcqapp.util.FontScale.DEFAULT)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.preview_the_quick_brown_fox_jumps_over_13_lazy_dogs),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.load_remote_images),
            help = stringResource(R.string.when_off_pictures_from_the_web_stay_hidden_so_whoever_aut),
            checked = loadRemoteImages,
            onChange = onLoadRemoteImagesChange,
            testTag = "setting-load-remote-images"
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.feedback), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SwitchSettingRow(
            label = stringResource(R.string.haptic_feedback),
            help = stringResource(R.string.haptic_feedback_help),
            checked = hapticsEnabled,
            onChange = { enabled ->
                // Sample the effect as it is switched on. The
                // switch is still off here, so this bypasses
                // the app gate deliberately; the platform's
                // touch-feedback setting still applies.
                if (enabled) {
                    feedback.performHapticFeedback(
                        HapticFeedbackType.ToggleOn
                    )
                }
                onHapticsChange(enabled)
            },
            testTag = "setting-haptics"
        )
    }
}

/**
 * A compact preview of a theme: background, input surface, then the three
 * accents. The border keeps a pale swatch (Solarized, Catppuccin) from
 * disappearing into the row behind it.
 */
@Composable
private fun ThemeSwatches(colors: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}
