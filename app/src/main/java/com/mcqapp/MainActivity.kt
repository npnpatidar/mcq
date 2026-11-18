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

/**
 * Decides whether a `SEND` intent should be turned into a shared-import
 * request. Extracted from [MainActivity] so the "handle exactly once per task"
 * rule is testable without launching an Activity, Compose, or Room.
 *
 * Android redelivers the *same* SEND intent to a recreated Activity on a
 * config change (e.g. rotation). Handling it in `onCreate` unconditionally
 * re-copies the file, re-requests the import and pushes `import/direct` again —
 * losing typed Import input and yanking the learner out of Study/Test. A fresh
 * create handles the intent; a recreation (`savedInstanceState != null`) and
 * the one-shot guard do not. A later, distinct share arrives via
 * `onNewIntent` and is always handled.
 */
internal class SendIntentRouter(private val onSendIntent: () -> Unit) {
    private var freshStartHandled = false

    /** Called from `Activity.onCreate`; [isRecreation] is `savedInstanceState != null`. */
    fun onActivityCreate(isSend: Boolean, isRecreation: Boolean) {
        if (isRecreation || freshStartHandled || !isSend) return
        freshStartHandled = true
        onSendIntent()
    }

    /** Called from `Activity.onNewIntent` for a subsequent, distinct share. */
    fun onNewIntent(isSend: Boolean) {
        if (isSend) onSendIntent()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var sendIntentRouter: SendIntentRouter

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
        sendIntentRouter = SendIntentRouter { handleSendIntent(intent) }
        sendIntentRouter.onActivityCreate(
            isSend = intent?.action == Intent.ACTION_SEND,
            isRecreation = savedInstanceState != null
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sendIntentRouter.onNewIntent(isSend = intent.action == Intent.ACTION_SEND)
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
