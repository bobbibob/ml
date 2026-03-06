package com.ml.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.CardTypeStore
import com.ml.app.data.PackDbSync
import com.ml.app.data.PackPaths
import com.ml.app.data.R2Client
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.CardType
import com.ml.app.domain.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class ScreenMode {
  data object Timeline : ScreenMode()
  data class Details(val date: LocalDate) : ScreenMode()
  data object ArticlePicker : ScreenMode()
  data class ArticleEditor(val bagId: String? = null) : ScreenMode()

}

data class SummaryState(
  val mode: ScreenMode = ScreenMode.Timeline,
  val selectedDate: LocalDate = LocalDate.now(),

  val timeline: List<DaySummary> = emptyList(),
  val rows: List<BagDayRow> = emptyList(),

  val cardTypes: Map<String, CardType> = emptyMap(),

  val status: String = "",
  val loading: Boolean = false,
  val hasPack: Boolean = false
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val repo = SQLiteRepo(ctx)
  private val r2 = R2Client(ctx)
  private val typeStore = CardTypeStore(ctx)

  private val prefsPack = ctx.getSharedPreferences("ml_pack", Context.MODE_PRIVATE)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  fun init() {
    viewModelScope.launch(Dispatchers.IO) {
      val has = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
      _state.value = _state.value.copy(hasPack = has)

      if (has) PackDbSync.refreshMergedDb(ctx)

      syncIfChanged()

      if (_state.value.hasPack) refreshTimeline()
    }
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading…", mode = ScreenMode.Timeline)
        val t = repo.loadTimeline(limitDays = 180)

        val ids = t.flatMap { it.byBags }.map { it.bagId }.distinct()
        val types = typeStore.getTypes(ids)

        _state.value = _state.value.copy(timeline = t, cardTypes = types, loading = false, status = "OK")
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

  

  fun openArticlePicker() {
    _state.value = _state.value.copy(mode = ScreenMode.ArticlePicker)
  }

  fun backFromArticlePicker() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun openArticleEditor(bagId: String? = null) {
    _state.value = _state.value.copy(mode = ScreenMode.ArticleEditor(bagId))
  }

  fun backFromArticleEditor() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
fun setDateFromPicker(date: LocalDate) {
    openDetails(date)
  }

  fun refreshDetails() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading…")
        val rows = repo.loadForDate(_state.value.selectedDate.toString())

        val ids = rows.map { it.bagId }.distinct()
        val types = typeStore.getTypes(ids)

        _state.value = _state.value.copy(rows = rows, cardTypes = types, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Error: ${t.message}")
      }
    }
  }

  fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Checking updates…")

        val remote = r2.headPack()
        val remoteEtag = remote.etag?.trim()?.trim('"') ?: ""
        val remoteToken = when {
          remoteEtag.isNotBlank() -> "etag:$remoteEtag"
          !remote.lastModified.isNullOrBlank() -> "lm:${remote.lastModified}"
          remote.contentLength != null -> "len:${remote.contentLength}"
          else -> ""
        }

        val localEtag = prefsPack.getString("etag", "") ?: ""
        val localToken = prefsPack.getString("pack_token", "")?.ifBlank {
          if (localEtag.isNotBlank()) "etag:$localEtag" else ""
        } ?: ""

        val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()

        if (!hasLocal) {
          _state.value = _state.value.copy(status = "Downloading…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.refreshMergedDb(ctx)

          prefsPack.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Downloaded")
          refreshAfterSync()
          return@launch
        }

        if (remoteToken.isNotBlank() && remoteToken != localToken) {
          _state.value = _state.value.copy(status = "Updating…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.refreshMergedDb(ctx)

          prefsPack.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Updated")
          refreshAfterSync()
        } else {
          _state.value = _state.value.copy(loading = false, status = "No changes")
          refreshAfterSync()
        }
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Sync error: ${t.message}")
      }
    }
  }
  private fun refreshAfterSync() {
    when (state.value.mode) {
        is ScreenMode.Timeline -> refreshTimeline()
        is ScreenMode.Details -> refreshDetails()
        is ScreenMode.ArticlePicker -> { 
  }
}
