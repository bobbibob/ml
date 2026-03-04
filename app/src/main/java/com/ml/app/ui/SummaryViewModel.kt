package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.PackPaths
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
import com.ml.app.data.SQLiteRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class ScreenMode {
  data object Timeline : ScreenMode()
  data class Details(val date: LocalDate) : ScreenMode()
}

data class SummaryState(
  val mode: ScreenMode = ScreenMode.Timeline,
  val selectedDate: LocalDate = LocalDate.now(),

  // timeline
  val timeline: List<DaySummary> = emptyList(),

  // details
  val rows: List<BagDayRow> = emptyList(),

  val status: String = "",
  val loading: Boolean = false,
  val hasPack: Boolean = false
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val repo = SQLiteRepo(ctx)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  fun init() {
    val has = PackPaths.dbFile(ctx).exists()
    _state.value = _state.value.copy(hasPack = has)
    if (has) refreshTimeline()
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading timeline…", mode = ScreenMode.Timeline)
        val t = repo.loadTimeline(limitDays = 180)
        _state.value = _state.value.copy(timeline = t, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Error: ${t.message}")
      }
    }
  }

  fun openDetails(date: LocalDate) {
    _state.value = _state.value.copy(selectedDate = date, mode = ScreenMode.Details(date))
    refreshDetails()
  }

  fun backToTimeline() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun setDateFromPicker(date: LocalDate) {
    // на таймлайне — сразу открываем детали
    openDetails(date)
  }

  fun refreshDetails() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading details…")
        val rows = repo.loadForDate(_state.value.selectedDate.toString())
        _state.value = _state.value.copy(rows = rows, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Error: ${t.message}")
      }
    }
  }
}
