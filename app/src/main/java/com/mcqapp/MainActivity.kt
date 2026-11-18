package com.mcqapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mcqapp.ui.common.Announcer
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.ui.haptics.LocalHapticsEnabled
import com.mcqapp.ui.navigation.McqNavHost
import com.mcqapp.ui.theme.AppTheme
import com.mcqapp.ui.theme.CustomThemeCodec
import com.mcqapp.ui.theme.McqTheme
import com.mcqapp.util.SendCopyResult
import com.mcqapp.util.SharedImport
import com.mcqapp.util.copySendToCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context?) {
        // Below API 33 this is the only hook that changes the resources Compose
        // reads. On 33+ AppLocale leaves the context alone: the platform has
        // already applied the app's persisted locales to it.
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

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
            // Silent until DataStore has emitted, so an install that switched
            // haptics off never pulses on the first frames of a launch.
            val hapticsEnabled by repository.hapticsEnabled()
                .collectAsStateWithLifecycle(initialValue = false)
            // The activity root view is the accessibility node that owns the
            // window, so announcing through it reaches TalkBack regardless of
            // which screen is composed. Provided once here; every screen reads
            // LocalAnnouncer instead of reaching for a Context/Toast.
            val rootView = LocalView.current
            val announcer = remember(rootView) {
                Announcer { text -> rootView.announceForAccessibility(text) }
            }
            McqTheme(
                themeMode = themeMode,
                themePalette = themePalette,
                customThemes = customThemes
            ) {
                CompositionLocalProvider(
                    LocalHapticsEnabled provides hapticsEnabled,
                    LocalAnnouncer provides announcer
                ) {
                    McqNavHost(repository = repository)
                }
            }
        }
        handleSendIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    /**
     * A file shared to the app: the stream is copied into `cacheDir/imports`
     * immediately (the sender's one-shot grant does not survive process
     * death), then the copy is handed to the nav host's router.
     */
    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { copySendToCache(this@MainActivity, intent) }
            when (result) {
                is SendCopyResult.Ready ->
                    SharedImport.request(result.request.uri, result.request.displayName)
                is SendCopyResult.Failed ->
                    SharedImport.requestError(result.resId, result.args)
            }
        }
    }
}
