package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.CardTypeStore
import com.ml.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class ScreenMode {
    object Timeline : ScreenMode()
    object Details : ScreenMode()
    object ArticlePicker : ScreenMode()
    data class ArticleEditor(val bagId: String?) : ScreenMode()
}

data class SummaryState(
    val mode: ScreenMode = ScreenMode.Timeline,
    val selectedDate: LocalDate = LocalDate.now(),
    val timeline: List<DaySummary> = emptyList(),
    val rows: List<BagDayRow> = emptyList(),
    val cardTypes: Map<String, CardType> = emptyMap(),
    val bagsList: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = false,
    val hasPack: Boolean = false,
    val status: String = ""
)

class SummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SQLiteRepo(application)
    private val typeStore = CardTypeStore(application)
    private val _state = MutableStateFlow(SummaryState())
    val state = _state.asStateFlow()

    fun init() { refreshTimeline() }

    fun refreshTimeline() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, hasPack = true) }
            val data = repo.loadTimeline()
            _state.update { it.copy(timeline = data, loading = false, mode = ScreenMode.Timeline) }
        }
    }

    fun openDetails(date: LocalDate) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, selectedDate = date) }
            val data = repo.loadForDate(date.toString())
            val ids = data.map { it.bagId }
            val types = typeStore.getTypes(ids)
            _state.update { it.copy(rows = data, cardTypes = types, loading = false, mode = ScreenMode.Details) }
        }
    }

    fun openArticlePicker() {
        viewModelScope.launch {
            val bags = repo.listAllBags()
            _state.update { it.copy(mode = ScreenMode.ArticlePicker, bagsList = bags) }
        }
    }

    fun openArticleEditor(bagId: String?) {
        _state.update { it.copy(mode = ScreenMode.ArticleEditor(bagId)) }
    }

    fun backToTimeline() { refreshTimeline() }
    fun backFromArticlePicker() = backToTimeline()
    fun backFromArticleEditor() = openArticlePicker()

    fun syncIfChanged() {
        // Здесь будет вызов R2Client.head/download
        if (state.value.mode is ScreenMode.Details) {
            openDetails(state.value.selectedDate)
        } else {
            refreshTimeline()
        }
    }
}
