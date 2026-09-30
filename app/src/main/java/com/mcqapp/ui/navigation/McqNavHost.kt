package com.mcqapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.ui.browse.BrowseScreen
import com.mcqapp.ui.editor.QuestionEditorScreen
import com.mcqapp.ui.history.BookmarksScreen
import com.mcqapp.ui.history.HistoryScreen
import com.mcqapp.ui.importscreen.ImportScreen
import com.mcqapp.ui.library.LibraryScreen
import com.mcqapp.ui.results.ResultsScreen
import com.mcqapp.ui.search.SearchScreen
import com.mcqapp.ui.settings.SettingsScreen
import com.mcqapp.ui.study.StudyScreen
import com.mcqapp.ui.test.TestSessionScreen
import com.mcqapp.util.Logger

@Composable
fun McqNavHost(repository: McqRepository) {
    val navController = rememberNavController()
    val fontScale by repository.fontScale().collectAsStateWithLifecycle(
        initialValue = com.mcqapp.util.FontScale.DEFAULT
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = fontScale),
        com.mcqapp.util.FontScale.LocalScale provides fontScale
    ) {
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(repository = repository, navController = navController)
        }
        composable(
            route = "test?paperId={paperId}&categories={categories}&mistakes={mistakes}&drillCount={drillCount}&drillMinutes={drillMinutes}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("categories") { type = NavType.StringType; defaultValue = "" },
                navArgument("mistakes") { type = NavType.BoolType; defaultValue = false },
                navArgument("drillCount") { type = NavType.IntType; defaultValue = 0 },
                navArgument("drillMinutes") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val categories = entry.arguments?.getString("categories").orEmpty()
            val mistakes = entry.arguments?.getBoolean("mistakes") ?: false
            val drillCount = entry.arguments?.getInt("drillCount") ?: 0
            val drillMinutes = entry.arguments?.getInt("drillMinutes") ?: 0
            Logger.i("NAV", "test screen: paperId=$paperId, categories='$categories', " +
                "mistakes=$mistakes, drill=$drillCount/${drillMinutes}min")
            TestSessionScreen(
                repository = repository,
                paperId = paperId,
                categoryIds = categories.split(",").filter { it.isNotBlank() },
                mistakesOnly = mistakes,
                drillCount = drillCount,
                drillMinutes = drillMinutes,
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
        composable(
            route = "browse/{paperId}?focus={focus}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("focus") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val focus = entry.arguments?.getString("focus").orEmpty()
            Logger.i("NAV", "browse screen: paperId=$paperId, focus='$focus'")
            BrowseScreen(
                repository = repository,
                paperId = paperId,
                focusQuestionId = focus,
                navController = navController
            )
        }
        composable("bookmarks") {
            BookmarksScreen(repository = repository, navController = navController)
        }
        composable("search") {
            Logger.i("NAV", "search screen")
            SearchScreen(repository = repository, navController = navController)
        }
        composable(
            route = "editor?questionId={questionId}&paperId={paperId}&categoryId={categoryId}",
            arguments = listOf(
                navArgument("questionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("paperId") { type = NavType.StringType; defaultValue = "" },
                navArgument("categoryId") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            Logger.i(
                "NAV", "editor screen: questionId=${entry.arguments?.getString("questionId")}, " +
                    "paperId=${entry.arguments?.getString("paperId")}, " +
                    "categoryId=${entry.arguments?.getString("categoryId")}"
            )
            QuestionEditorScreen(
                repository = repository,
                questionId = entry.arguments?.getString("questionId").orEmpty(),
                paperId = entry.arguments?.getString("paperId").orEmpty(),
                categoryId = entry.arguments?.getString("categoryId").orEmpty(),
                navController = navController
            )
        }
        composable(
            route = "study/{paperId}",
            arguments = listOf(navArgument("paperId") { type = NavType.StringType })
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            Logger.i("NAV", "study screen: paperId=$paperId")
            StudyScreen(
                repository = repository,
                paperId = paperId,
                navController = navController
            )
        }
        composable("settings") {
            SettingsScreen(repository = repository, navController = navController)
        }
        composable("import/direct") {
            val text = com.mcqapp.ui.importscreen.ImportDataHolder.pendingJsonText
            Logger.i("NAV", "import screen: direct text import")
            ImportScreen(importText = text, navController = navController)
        }
    }
    }
}
