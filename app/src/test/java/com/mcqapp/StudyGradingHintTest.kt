package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.repository.dataStore
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The persisted half of the one-time grading hint: it is read once at session
 * start and written on dismissal, so a fresh ViewModel (process death) never
 * shows it again. Robolectric + the real DataStore/repository, like
 * [StudyViewModelPullTest] these touch the process-wide database and run on
 * CI's x86_64 runners rather than the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyGradingHintTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @Before
    fun clearHintFlag() = runBlocking {
        app.dataStore.edit { it.remove(booleanPreferencesKey("study_grading_hint_shown")) }
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("p1")
        app.dataStore.edit { it.remove(booleanPreferencesKey("study_grading_hint_shown")) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun seedOneQuestion() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "Q q1?",
                options = listOf(
                    QuestionOption("q1-a", "A"),
                    QuestionOption("q1-b", "B")
                ),
                correctOptionIds = setOf("q1-a")
            )
        )
    }

    private fun flagPersisted(): Boolean = runBlocking {
        repository.studyGradingHintShown().first()
    }

    @Test
    fun hintShowsOnFirstSessionThenDismissHidesAndPersists() {
        seedOneQuestion()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current != null
        })
        assertTrue("the first study screen must show the hint", viewModel.state.value.showGradingHint)

        viewModel.dismissGradingHint()

        assertFalse("dismiss hides the hint at once", viewModel.state.value.showGradingHint)
        assertTrue("the dismissal is written to storage", awaitCondition { flagPersisted() })
    }

    @Test
    fun dismissalSurvivesRecreation() {
        seedOneQuestion()
        val first = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { first.state.value.current != null })
        first.dismissGradingHint()
        assertTrue("persist before recreating", awaitCondition { flagPersisted() })

        // A process death builds a fresh ViewModel that re-reads the flag.
        val recreated = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { recreated.state.value.current != null })
        assertFalse("a recreated session must not show the hint again", recreated.state.value.showGradingHint)
    }
}
