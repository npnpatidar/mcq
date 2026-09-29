package com.mcqapp.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mcqapp.ui.browse.BrowseViewModel
import com.mcqapp.ui.editor.EditorViewModel
import com.mcqapp.ui.results.ResultsViewModel
import com.mcqapp.ui.test.TestViewModel

class TestViewModelFactory(
    private val application: Application,
    private val paperId: String,
    private val categoryIds: List<String>,
    private val mistakesOnly: Boolean = false,
    private val drillCount: Int = 0,
    private val drillMinutes: Int = 0
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TestViewModel(application, paperId, categoryIds, mistakesOnly, drillCount, drillMinutes) as T
    }
}

class ResultsViewModelFactory(
    private val application: Application,
    private val attemptId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ResultsViewModel(application, attemptId) as T
    }
}

class EditorViewModelFactory(
    private val application: Application,
    private val questionId: String,
    private val paperId: String,
    private val categoryId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EditorViewModel(application, questionId, paperId, categoryId) as T
    }
}

class BrowseViewModelFactory(
    private val application: Application,
    private val paperId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BrowseViewModel(application, paperId) as T
    }
}
