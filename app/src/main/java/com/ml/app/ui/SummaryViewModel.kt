package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SummaryState(
    val timeline: List<DaySummary> = emptyList(),
    val hasPack: Boolean = true, // Ставим true для теста
    val loading: Boolean = false,
    val status: String = "Тестовый режим",
    val mode: ScreenMode = ScreenMode.Timeline
)

sealed class ScreenMode {
    object Timeline : ScreenMode()
    object Details : ScreenMode()
    object ArticlePicker : ScreenMode()
    data class ArticleEditor(val bagId: String?) : ScreenMode()
}

class SummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SummaryState())
    val state = _state.asStateFlow()

    fun init() {
        // Временно ничего не грузим из базы, просто показываем пустой список
        _state.update { it.copy(status = "ViewModel инициализирована без БД") }
    }
    
    fun syncIfChanged() {}
    fun openArticlePicker() { _state.update { it.copy(mode = ScreenMode.ArticlePicker) } }
    fun backToTimeline() { _state.update { it.copy(mode = ScreenMode.Timeline) } }
    fun openArticleEditor(id: String?) { _state.update { it.copy(mode = ScreenMode.ArticleEditor(id)) } }
}
