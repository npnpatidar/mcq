package com.mcqapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcqapp.ui.navigation.McqNavHost
import com.mcqapp.ui.theme.AppTheme
import com.mcqapp.ui.theme.CustomThemeCodec
import com.mcqapp.ui.theme.McqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as McqApplication).repository
        setContent {
            val themeMode by repository.themeMode().collectAsStateWithLifecycle(initialValue = "system")
            val themePalette by repository.themePalette()
                .collectAsStateWithLifecycle(initialValue = AppTheme.MATERIAL.key)
            val customThemesJson by repository.customThemesJson()
                .collectAsStateWithLifecycle(initialValue = null)
            val customThemes = remember(customThemesJson) {
                CustomThemeCodec.decodeAll(customThemesJson)
            }
            McqTheme(
                themeMode = themeMode,
                themePalette = themePalette,
                customThemes = customThemes
            ) {
                McqNavHost(repository = repository)
            }
        }
    }
}
