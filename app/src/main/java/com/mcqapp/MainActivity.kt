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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a shared file needs to be copied: a stable application context (the
 * Activity itself is destroyed on rotation) and the SEND intent to copy.
 */
private data class SendPayload(val context: Context, val intent: Intent)

/**
 * Process-scoped coordinator for `ACTION_SEND` shares, extracted so the
 * "handle exactly once, never drop in flight" rule is testable without an
 * Activity, Compose or Room.
 *
 * Two bugs shaped this class. Handling SEND in `onCreate` unconditionally
 * re-imported on a config change (Android redelivers the same intent), losing
 * typed Import input and yanking the learner out of Study/Test. Gating on
 * `savedInstanceState == null` fixed that but mirrored the bug: the copy ran
 * on the Activity's `lifecycleScope`, which rotation cancels, so a share caught
 * mid-copy was silently dropped.
 *
 * The copy therefore runs on a scope that outlives the Activity, and de-dup is
 * by copy state rather than Activity instance: a fresh create with a SEND
 * intent starts one copy; a recreation (same intent, state COPYING/DONE) does
 * nothing — the copy either continues to completion or has already requested
 * the import. A distinct share via `onNewIntent` always starts a fresh copy.
 */
internal class SendIntentCoordinator<T>(
    private val scope: CoroutineScope,
    private val onSendIntent: suspend (T) -> Unit
) {
    private var state = State.IDLE

    /** A fresh or recreated Activity: handle only if no copy is running or done. */
    fun onActivityCreate(payload: T?, isSend: Boolean) {
        if (!isSend || payload == null || state != State.IDLE) return
        start(payload)
    }

    /** A subsequent, distinct share: always handled, even just after a copy finished. */
    fun onNewIntent(payload: T, isSend: Boolean) {
        if (!isSend) return
        start(payload)
    }

    private fun start(payload: T) {
        state = State.COPYING
        scope.launch {
            try {
                onSendIntent(payload)
            } finally {
                // Success or a reported copy failure: either way the request
                // path has run (or will not be retried), so suppress re-import.
                state = State.DONE
            }
        }
    }

    private enum class State { IDLE, COPYING, DONE }
}

class MainActivity : ComponentActivity() {
    companion object {
        /**
         * Outlives the Activity so rotation cannot cancel an in-flight copy:
         * the share always reaches SharedImport. Payloads carry the application
         * context, so no destroyed Activity is retained here.
         */
        private val sendCopyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val sendIntentCoordinator = SendIntentCoordinator<SendPayload>(sendCopyScope) { payload ->
            // A shared snippet (no file attached) is quick capture unless it is
            // plainly a bank. Files keep the existing copy -> import pipeline.
            val sharedText = if (com.mcqapp.util.sendStreamUri(payload.intent) == null) {
                payload.intent.getStringExtra(Intent.EXTRA_TEXT)
            } else null
            if (!sharedText.isNullOrBlank() &&
                !com.mcqapp.util.sharedTextIsBank(sharedText)
            ) {
                SharedImport.requestText(sharedText)
                return@SendIntentCoordinator
            }
            val result = withContext(Dispatchers.IO) {
                copySendToCache(payload.context, payload.intent)
            }
            when (result) {
                is SendCopyResult.Ready ->
                    SharedImport.request(result.request.uri, result.request.displayName)
                is SendCopyResult.Failed ->
                    SharedImport.requestError(result.resId, result.args)
            }
        }
    }

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
        intent?.let { currentIntent ->
            sendIntentCoordinator.onActivityCreate(
                payload = SendPayload(applicationContext, currentIntent),
                isSend = currentIntent.action == Intent.ACTION_SEND
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sendIntentCoordinator.onNewIntent(
            payload = SendPayload(applicationContext, intent),
            isSend = intent.action == Intent.ACTION_SEND
        )
    }
}
