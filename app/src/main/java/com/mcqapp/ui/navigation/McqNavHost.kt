package com.mcqapp.ui.navigation

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.ui.common.LocalUiMessageSender
import com.mcqapp.ui.common.MessageResult
import com.mcqapp.ui.common.UiMessage
import com.mcqapp.ui.common.UiMessageBus
import com.mcqapp.ui.common.UiMessagePresenter
import com.mcqapp.ui.common.UiMessageTone
import com.mcqapp.ui.browse.BrowseScreen
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

    // The single app-wide feedback host. Screens push UiMessages into the bus;
    // the presenter announces each one through the activity's announcer and
    // then presents it in this SnackbarHost. It replaces the app's Toasts so a
    // failure is both visible and spoken (WCAG 4.1.3).
    // See https://developer.android.com/develop/ui/compose/components/snackbar
    val messageBus = remember { UiMessageBus() }
    val snackbarHostState = remember { SnackbarHostState() }
    val announcer = LocalAnnouncer.current
    LaunchedEffect(messageBus, snackbarHostState, announcer) {
        UiMessagePresenter(
            announce = announcer::announce,
            show = { message ->
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
    LaunchedEffect(pendingImport) {
        val request = SharedImport.consume() ?: return@LaunchedEffect
        libraryViewModel.onImportPicked(
            request.uri,
            platformContext.contentResolver,
            onDocx = { bytes, name ->
                ImportDataHolder.pendingDocxBytes = bytes
                ImportDataHolder.pendingDocxName = name
                navController.navigate("import/direct")
            },
            onJsonUri = {
                ImportDataHolder.pendingJsonUri = it
                navController.navigate("import/direct")
            },
            onJsonBytes = {
                ImportDataHolder.pendingJsonBytes = it
                navController.navigate("import/direct")
            }
        )
    }
    LaunchedEffect(pendingImportError) {
        val message = SharedImport.consumeError() ?: return@LaunchedEffect
        // SharedImport's copy-failure copy is already plain and generic.
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
    // The outer Scaffold exists only to host the app-wide SnackbarHost. Its
    // innerPadding is not applied: screens own their own Scaffolds and insets,
    // and the snackbar simply floats above the current destination.
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
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
        composable("import/direct") {
            val bytes = com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonBytes
            val uri = com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonUri
            Logger.i("NAV", "import screen: direct json import, ${bytes?.size ?: 0} bytes, uri=$uri")
            ImportScreen(importBytes = bytes, importUri = uri, navController = navController)
        }
    }
    }
    }
}
