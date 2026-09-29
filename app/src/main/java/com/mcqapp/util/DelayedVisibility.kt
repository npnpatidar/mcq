package com.mcqapp.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * True only if [active] stays true past [delayMs]. Use for loading indicators:
 * fast loads never flash a spinner, genuinely slow ones still show feedback.
 */
@Composable
fun rememberDelayedVisibility(active: Boolean, delayMs: Long = 300): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return visible && active
}
