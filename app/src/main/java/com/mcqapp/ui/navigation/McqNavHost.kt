package com.mcqapp.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.MessageResult
import com.mcqapp.ui.common.ToneSnackbarHost
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageBus
import com.mcqapp.ui.common.UiMessagePresenter
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.ui.browse.BrowseScreen
import com.mcqapp.ui.editor.EditorDirtyGuard
import com.mcqapp.ui.editor.QuestionEditorScreen
import com.mcqapp.ui.history.BookmarksScreen
import com.mcqapp.ui.history.HistoryScreen
import com.mcqapp.ui.importscreen.ImportDataHolder
import com.mcqapp.ui.importscreen.ImportScreen
import com.mcqapp.ui.library.LibraryScreen
import com.mcqapp.ui.library.LibraryViewModel
import com.mcqapp.ui.results.ResultsScreen
import com.mcqapp.ui.search.SearchScreen
import com.mcqapp.ui.sessions.SessionsScreen
import com.mcqapp.ui.settings.SettingsScreen
import com.mcqapp.ui.study.StudyScreen
import com.mcqapp.ui.test.TestSessionScreen
import com.mcqapp.util.Logger
import com.mcqapp.util.SharedImport

@Composable
fun McqNavHost(repository: McqRepository) {
    val navController = rememberNavController()
    // One activity-scoped library VM: the picker and a shared file route into
    // the same classify pipeline, and the import report and error dialogs
    // the pipeline raises stay visible when the library is reached.
    val libraryViewModel: LibraryViewModel = viewModel()
    val platformContext = LocalContext.current

    // Round-2 E4: the editor mirrors its dirty flag app-wide
    // (EditorDirtyGuard). `NavController.navigate(String)` is final, so a
    // transparent subclass cannot intercept it; instead every navigation the
    // NavHost itself issues goes through this seam. The editor's own Back/top
    // bar paths already show their in-screen leave dialog; this closes the gap
    // where an externally-originated navigate (the SEND/import flow below)
    // used to pop a dirty editor silently.
    val editorDirty by EditorDirtyGuard.isDirty.collectAsStateWithLifecycle(
        initialValue = false
    )
    // rememberSaveable keeps a held exit through rotation while the leave
    // dialog is up; plain remember dropped it and stranded the staged SEND
    // payload. The custom saver handles the nullable String explicitly.
    var pendingExitDestination by rememberSaveable(saver = PendingExitSaver) {
        mutableStateOf<String?>(null)
    }
    // If the editor is saved/discarded through its own dialog while an
    // external exit is waiting, there is nothing left to confirm.
    LaunchedEffect(editorDirty) {
        if (!editorDirty) pendingExitDestination = null
    }
    // [bypass] is set only by the confirm dialog's own leave action: it must
    // never be blocked by the very guard it just satisfied, or the user could
    // not leave at all (deadlock).
    val navigateWithGuard: (String, Boolean) -> Unit = { destination, bypass ->
        when (EditorExitGuard.decide(EditorDirtyGuard.isDirty.value, destination, bypass)) {
            EditorExitDecision.CONFIRM -> pendingExitDestination = destination
            EditorExitDecision.PROCEED -> navController.navigate(destination)
        }
    }

    // The single app-wide feedback host. Screens push UiMessages into the bus;
    // the presenter announces each one through the activity's announcer and
    // then presents it in this SnackbarHost. It replaces the app's Toasts so a
    // failure is both visible and spoken (WCAG 4.1.3).
    // See https://developer.android.com/develop/ui/compose/components/snackbar
    val messageBus = remember { UiMessageBus() }
    val snackbarHostState = remember { SnackbarHostState() }
    // The presenter owns one message at a time, so the host can read the tone
    // of the message it is showing. Set in the same frame as showSnackbar; the
    // text is untouched, only the leading icon and tint follow the tone.
    var currentTone by remember { mutableStateOf(UiMessageTone.Info) }
    val announcer = LocalAnnouncer.current
    LaunchedEffect(messageBus, snackbarHostState, announcer) {
        UiMessagePresenter(
            announce = announcer::announce,
            show = { message ->
                currentTone = message.tone
                val result = snackbarHostState.showSnackbar(
                    message = message.text,
                    actionLabel = message.actionLabel,
                    duration = if (message.tone == UiMessageTone.Error) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    }
                )
                if (result == SnackbarResult.ActionPerformed) {
                    MessageResult.ActionPerformed
                } else {
                    MessageResult.Dismissed
                }
            }
        ).run(messageBus)
    }

    val pendingImport by SharedImport.pending.collectAsStateWithLifecycle()
    val pendingImportError by SharedImport.error.collectAsStateWithLifecycle()
    // Resolve in the composable body: LaunchedEffect's block is not @Composable.
    val importFailure = pendingImportError
    val importErrorMessage = if (importFailure != null) {
        stringResource(importFailure.resId, *importFailure.args.toTypedArray())
    } else null
    LaunchedEffect(pendingImport) {
        val request = SharedImport.consume() ?: return@LaunchedEffect
        libraryViewModel.onImportPicked(
            request.uri,
            platformContext.contentResolver,
            onDocx = { bytes, name ->
                ImportDataHolder.pendingDocxBytes = bytes
                ImportDataHolder.pendingDocxName = name
                navigateWithGuard("import/direct", false)
            },
            onJsonUri = {
                ImportDataHolder.pendingJsonUri = it
                navigateWithGuard("import/direct", false)
            },
            onJsonBytes = {
                ImportDataHolder.pendingJsonBytes = it
                navigateWithGuard("import/direct", false)
            }
        )
    }
    LaunchedEffect(pendingImportError) {
        if (SharedImport.consumeError() == null) return@LaunchedEffect
        val message = importErrorMessage ?: return@LaunchedEffect
        messageBus.send(UiMessage(message, UiMessageTone.Error))
    }
    val fontScale by repository.fontScale().collectAsStateWithLifecycle(
        initialValue = com.mcqapp.util.FontScale.DEFAULT
    )
    val loadRemoteImages by repository.loadRemoteImages().collectAsStateWithLifecycle(
        initialValue = false
    )
    val density = LocalDensity.current
    // Stable sender: a fresh lambda on every recomposition would invalidate
    // every screen reading LocalUiMessageSender and churn the whole tree.
    val messageSender = remember(messageBus) { { message: UiMessage -> messageBus.send(message) } }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = fontScale),
        com.mcqapp.util.FontScale.LocalScale provides fontScale,
        com.mcqapp.util.LocalLoadRemoteImages provides loadRemoteImages,
        LocalUiMessageSender provides messageSender
    ) {
    // A Box overlay hosts the app-wide SnackbarHost instead of a Scaffold:
    // each screen owns its own Scaffold and insets, so applying an outer
    // Scaffold's padding would double-apply them. The snackbar just floats
    // above the current destination.
    Box(Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                repository = repository,
                navController = navController,
                viewModel = libraryViewModel
            )
        }
        composable(
            route = "test?paperId={paperId}&categories={categories}&mistakes={mistakes}&drillCount={drillCount}&drillMinutes={drillMinutes}&minutes={minutes}&type={type}&count={count}&exclusion={exclusion}&breadth={breadth}&variant={variant}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("categories") { type = NavType.StringType; defaultValue = "" },
                navArgument("mistakes") { type = NavType.BoolType; defaultValue = false },
                navArgument("drillCount") { type = NavType.IntType; defaultValue = 0 },
                navArgument("drillMinutes") { type = NavType.IntType; defaultValue = 0 },
                navArgument("minutes") { type = NavType.IntType; defaultValue = -1 },
                navArgument("type") { type = NavType.StringType; defaultValue = "" },
                navArgument("count") { type = NavType.IntType; defaultValue = 0 },
                navArgument("exclusion") { type = NavType.StringType; defaultValue = "" },
                navArgument("breadth") { type = NavType.StringType; defaultValue = "" },
                navArgument("variant") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val categories = entry.arguments?.getString("categories").orEmpty()
            val mistakes = entry.arguments?.getBoolean("mistakes") ?: false
            val drillCount = entry.arguments?.getInt("drillCount") ?: 0
            val drillMinutes = entry.arguments?.getInt("drillMinutes") ?: 0
            val minutes = entry.arguments?.getInt("minutes") ?: -1
            val type = entry.arguments?.getString("type").orEmpty()
            val count = entry.arguments?.getInt("count") ?: 0
            val exclusion = entry.arguments?.getString("exclusion").orEmpty()
            val breadth = entry.arguments?.getString("breadth").orEmpty()
            val variant = entry.arguments?.getString("variant").orEmpty()
            Logger.i("NAV", "test screen: paperId=$paperId, categories='$categories', " +
                "mistakes=$mistakes, drill=$drillCount/${drillMinutes}min, minutes=$minutes, " +
                "type='$type', count=$count, exclusion='$exclusion', breadth='$breadth', variant='$variant'")
            TestSessionScreen(
                repository = repository,
                paperId = paperId,
                categoryIds = decodeCategoryList(categories),
                mistakesOnly = mistakes,
                drillCount = drillCount,
                drillMinutes = drillMinutes,
                durationMinutes = minutes,
                testType = type,
                requestedCount = count,
                exclusionMode = exclusion,
                breadth = breadth,
                variant = variant,
                navController = navController
            )
        }
        composable(
            route = "results/{attemptId}",
            arguments = listOf(navArgument("attemptId") { type = NavType.LongType })
        ) { entry ->
            val attemptId = entry.arguments?.getLong("attemptId") ?: 0L
            Logger.i("NAV", "results screen: attemptId=$attemptId")
            ResultsScreen(
                repository = repository,
                attemptId = attemptId,
                navController = navController,
                reviewMode = false
            )
        }
        composable(
            route = "review/{attemptId}",
            arguments = listOf(navArgument("attemptId") { type = NavType.LongType })
        ) { entry ->
            val attemptId = entry.arguments?.getLong("attemptId") ?: 0L
            ResultsScreen(
                repository = repository,
                attemptId = attemptId,
                navController = navController,
                reviewMode = true
            )
        }
        composable("history") {
            HistoryScreen(repository = repository, navController = navController)
        }
        composable("sessions") {
            Logger.i("NAV", "sessions screen")
            SessionsScreen(repository = repository, navController = navController)
        }
        composable("stats") {
            Logger.i("NAV", "stats screen")
            com.mcqapp.ui.stats.StatsScreen(repository = repository, navController = navController)
        }
        composable(
            route = "browse/{paperId}?focus={focus}&categories={categories}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("focus") { type = NavType.StringType; defaultValue = "" },
                navArgument("categories") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val focus = entry.arguments?.getString("focus").orEmpty()
            val categories = decodeCategoryList(
                entry.arguments?.getString("categories").orEmpty()
            )
            Logger.i("NAV", "browse screen: paperId=$paperId, focus='$focus', categories=${categories.size}")
            BrowseScreen(
                repository = repository,
                paperId = paperId,
                focusQuestionId = focus,
                initialCategoryIds = categories,
                navController = navController
            )
        }
        composable("bookmarks") {
            BookmarksScreen(repository = repository, navController = navController)
        }
        composable("search") {
            Logger.i("NAV", "search screen")
            SearchScreen(navController = navController)
        }
        composable(
            route = "editor?questionId={questionId}&paperId={paperId}&categoryId={categoryId}&source={source}",
            arguments = listOf(
                navArgument("questionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("paperId") { type = NavType.StringType; defaultValue = "" },
                navArgument("categoryId") { type = NavType.StringType; defaultValue = "" },
                // Absent on links built before a source existed; those infer
                // database/new from the id, exactly as before.
                navArgument("source") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            Logger.i(
                "NAV", "editor screen: questionId=${entry.arguments?.getString("questionId")}, " +
                    "paperId=${entry.arguments?.getString("paperId")}, " +
                    "categoryId=${entry.arguments?.getString("categoryId")}, " +
                    "source='${entry.arguments?.getString("source")}'"
            )
            QuestionEditorScreen(
                repository = repository,
                questionId = entry.arguments?.getString("questionId").orEmpty(),
                paperId = entry.arguments?.getString("paperId").orEmpty(),
                categoryId = entry.arguments?.getString("categoryId").orEmpty(),
                source = entry.arguments?.getString("source").orEmpty(),
                navController = navController
            )
        }
        composable(
            route = "study/{paperId}?categories={categories}&leechesOnly={leechesOnly}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("categories") { type = NavType.StringType; defaultValue = "" },
                navArgument("leechesOnly") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val categories = decodeCategoryList(
                entry.arguments?.getString("categories").orEmpty()
            )
            val leechesOnly = entry.arguments?.getBoolean("leechesOnly") ?: false
            Logger.i(
                "NAV",
                "study screen: paperId=$paperId, categories=$categories, leechesOnly=$leechesOnly"
            )
            StudyScreen(
                repository = repository,
                paperId = paperId,
                categoryIds = categories,
                leechesOnly = leechesOnly,
                navController = navController
            )
        }
        composable("passages") {
            Logger.i("NAV", "passages screen")
            com.mcqapp.ui.editor.PassagesScreen(
                repository = repository,
                navController = navController
            )
        }
        composable("settings") {
            SettingsScreen(repository = repository, navController = navController)
        }
        composable("ai-schemas") {
            com.mcqapp.ui.settings.AiSchemaScreen(navController = navController)
        }
        composable("import/direct") {
            val bytes = com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes
            val uri = com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri
            Logger.i("NAV", "import screen: direct json import, ${bytes?.size ?: 0} bytes, uri=$uri")
            ImportScreen(importBytes = bytes, importUri = uri, navController = navController)
        }
    }
    ToneSnackbarHost(
        hostState = snackbarHostState,
        tone = currentTone,
        modifier = Modifier.align(Alignment.BottomCenter)
    )

    // Round-2 E4: a blocked external exit surfaces the same leave copy the
    // editor uses. Discard drops the unsaved edit and performs the held
    // navigation with bypass = true; Cancel keeps the editor on screen. The
    // held destination is remembered, so the SEND/import payload staged before
    // the block is still delivered when the learner chooses to leave.
    val pendingExit = pendingExitDestination
    if (pendingExit != null && editorDirty) {
        AlertDialog(
            onDismissRequest = { pendingExitDestination = null },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingExitDestination = null
                    EditorDirtyGuard.clear()
                    navigateWithGuard(pendingExit, true)
                }) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExitDestination = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    }
    }
}

/**
 * Round-2 E4 decision seam: whether a navigation request would silently leave
 * a dirty editor. Pure JVM code so the guard can be tested without Compose,
 * Room or a NavController; the NavHost owns the dialog and the actual navigate.
 */
internal enum class EditorExitDecision { PROCEED, CONFIRM }

/** Rotation-safe encoding of the held exit destination. Route strings are
 *  never empty, so "" is an unambiguous sentinel for "nothing pending". */
internal fun encodePendingExit(destination: String?): String = destination.orEmpty()
internal fun decodePendingExit(saved: String): String? = saved.ifEmpty { null }

/** Saves the pending leave-confirmation destination across configuration
 *  changes so a rotation cannot silently drop the staged SEND/import nav. */
internal val PendingExitSaver = Saver<MutableState<String?>, String>(
    save = { state -> encodePendingExit(state.value) },
    restore = { saved -> mutableStateOf(decodePendingExit(saved)) }
)

internal object EditorExitGuard {
    /** Both the route template and filled editor routes start with "editor". */
    fun isEditorRoute(route: String?): Boolean =
        route != null && (route == "editor" || route.startsWith("editor?"))

    /**
     * CONFIRM when [destination] would leave an editor that still has unsaved
     * edits (a null destination is a pop, which also leaves it); the caller
     * must surface the leave confirmation and retry with [bypass] = true once
     * the user chooses to leave. PROCEED otherwise:
     *  - `!isDirty`: nothing unsaved, so nothing to confirm;
     *  - an editor destination: we are not leaving the editor;
     *  - `bypass`: the confirmation's own leave action must never be blocked
     *    by the guard it just satisfied (no deadlock).
     */
    fun decide(isDirty: Boolean, destination: String?, bypass: Boolean): EditorExitDecision =
        if (!bypass && isDirty && !isEditorRoute(destination)) {
            EditorExitDecision.CONFIRM
        } else {
            EditorExitDecision.PROCEED
        }
}
