package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The app-wide Snackbar host with a tone cue. The message text is exactly what
 * the sender supplied; only a leading icon and a subtle tint are added. The
 * tint reads Material 3 roles, so a custom theme's palette and contrast carry
 * through instead of a hardcoded colour.
 *
 * [tone] is the tone of the snackbar currently being shown. There is one
 * consumer, and the host's presenter sets it in the same frame it calls
 * `showSnackbar`, so the two cannot drift for a given message.
 */
@Composable
internal fun ToneSnackbarHost(
    hostState: SnackbarHostState,
    tone: UiMessageTone,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState, modifier) { data ->
        ToneSnackbar(data, tone)
    }
}

@Composable
private fun ToneSnackbar(data: SnackbarData, tone: UiMessageTone) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val actionLabel = data.visuals.actionLabel
    Snackbar(
        containerColor = container,
        contentColor = contentColorFor(container),
        action = if (actionLabel != null) {
            { TextButton(onClick = { data.performAction() }) { Text(actionLabel) } }
        } else {
            null
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the message text is the primary signal and is never
            // rewritten, so screen readers hear it once, not "Warning ...".
            Icon(
                toneImageVector(tone.icon),
                contentDescription = null,
                tint = toneRoleColor(tone.role)
            )
            Spacer(Modifier.width(8.dp))
            Text(data.visuals.message)
        }
    }
}

/** The glyph for a tone's icon kind. */
internal fun toneImageVector(icon: ToneIcon): ImageVector = when (icon) {
    ToneIcon.Check -> Icons.Filled.CheckCircle
    ToneIcon.Info -> Icons.Filled.Info
    ToneIcon.Warning -> Icons.Filled.Warning
}

/**
 * The tint for a tone's role, read from the active theme. A role colour (not an
 * RGB literal) keeps the snackbar legible in light, dark and custom palettes.
 */
@Composable
internal fun toneRoleColor(role: ToneRole) = when (role) {
    ToneRole.Primary -> MaterialTheme.colorScheme.primary
    ToneRole.Secondary -> MaterialTheme.colorScheme.secondary
    ToneRole.Error -> MaterialTheme.colorScheme.error
}
