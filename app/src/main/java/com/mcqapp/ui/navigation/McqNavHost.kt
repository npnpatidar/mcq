package com.mcqapp.ui.navigation

import androidx.compose.runtime.Composable
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
import com.mcqapp.ui.library.LibraryScreen
import com.mcqapp.ui.results.ResultsScreen
import com.mcqapp.ui.settings.SettingsScreen
import com.mcqapp.ui.test.TestSessionScreen
import com.mcqapp.util.Logger

@Composable
fun McqNavHost(repository: McqRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(repository = repository, navController = navController)
        }
        composable(
            route = "test?paperId={paperId}&categories={categories}",
            arguments = listOf(
                navArgument("paperId") { type = NavType.StringType },
                navArgument("categories") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            val categories = entry.arguments?.getString("categories").orEmpty()
            Logger.i("NAV", "test screen: paperId=$paperId, categories='$categories'")
            TestSessionScreen(
                repository = repository,
                paperId = paperId,
                categoryIds = categories.split(",").filter { it.isNotBlank() },
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
            route = "browse/{paperId}",
            arguments = listOf(navArgument("paperId") { type = NavType.StringType })
        ) { entry ->
            val paperId = entry.arguments?.getString("paperId").orEmpty()
            Logger.i("NAV", "browse screen: paperId=$paperId")
            BrowseScreen(repository = repository, paperId = paperId, navController = navController)
        }
        composable("bookmarks") {
            BookmarksScreen(repository = repository, navController = navController)
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
        composable("settings") {
            SettingsScreen(repository = repository, navController = navController)
        }
    }
}
