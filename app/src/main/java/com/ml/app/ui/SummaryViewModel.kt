package com.ml.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.PackPaths
import com.ml.app.data.R2Client
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.DayDetails
import com.ml.app.domain.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class ScreenMode { Timeline, Details }

data class UiState(
  val loading: Boolean = false,
  val status: String = "",
  val hasPack: Boolean = false,
  val mode: ScreenMode = ScreenMode.Timeline,
  val timeline: List<DaySummary> = emptyList(),
  val selectedDate: LocalDate? = null,
  val details: DayDetails? = null
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx: Context = app.applicationContext
  private val prefs = ctx.getSharedPreferences("ml", Context.MODE_PRIVATE)

  private val r2 = R2Client(ctx)
  private val repo = SQLiteRepo(ctx)

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state

  fun init() {
    viewModelScope.launch(Dispatchers.IO) {
      val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
      _state.value = _state.value.copy(hasPack = hasLocal)

      // check updates (or download on first start)
      syncIfChanged()

      // then show timeline
      if (_state.value.hasPack) refreshTimeline()
    }
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading…", mode = ScreenMode.Timeline)
        val t = repo.loadTimeline(limitDays = 180)
        _state.value = _state.value.copy(timeline = t, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "DB error: ${t.message ?: t.javaClass.simpleName}")
      }
    }
  }

  fun openDetails(date: LocalDate) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Loading…", mode = ScreenMode.Details, selectedDate = date)
        val d = repo.loadDayDetails(date.toString())
        _state.value = _state.value.copy(details = d, loading = false, status = "OK")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "DB error: ${t.message ?: t.javaClass.simpleName}")
      }
    }
  }

  fun backToTimeline() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline, details = null)
  }

  fun setDateFromPicker(date: LocalDate) {
    openDetails(date)
  }

  fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Checking updates…")

        val remote = r2.headPack()
        val remoteEtag = remote.etag?.trim()?.trim('"') ?: ""

        // Some setups may not return ETag reliably. Use a fallback token so sync still works.
        val remoteToken = when {
          remoteEtag.isNotBlank() -> "etag:$remoteEtag"
          !remote.lastModified.isNullOrBlank() -> "lm:${remote.lastModified}"
          remote.contentLength != null -> "len:${remote.contentLength}"
          else -> ""
        }

        val localEtag = prefs.getString("etag", "") ?: ""
        val localToken = prefs.getString("pack_token", "")?.ifBlank {
          if (localEtag.isNotBlank()) "etag:$localEtag" else ""
        } ?: ""

        val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()

        if (!hasLocal) {
          // first install: must download
          _state.value = _state.value.copy(status = "Downloading…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          prefs.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Downloaded")
          return@launch
        }

        if (remoteToken.isNotBlank() && remoteToken != localToken) {
          _state.value = _state.value.copy(status = "Updating…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          prefs.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Updated")
        } else {
          _state.value = _state.value.copy(loading = false, status = "No changes")
        }
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Sync error: ${t.message ?: t.javaClass.simpleName}")
      }
    }
  }
}
