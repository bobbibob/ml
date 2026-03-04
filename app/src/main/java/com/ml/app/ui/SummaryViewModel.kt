package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.ManifestUtil
import com.ml.app.data.PackPaths
import com.ml.app.data.R2Client
import com.ml.app.data.RemotePackMeta
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

sealed class ScreenMode {
  data object Timeline : ScreenMode()
  data class Details(val date: LocalDate) : ScreenMode()
}

data class SummaryState(
  val mode: ScreenMode = ScreenMode.Timeline,
  val selectedDate: LocalDate = LocalDate.now(),
  val timeline: List<DaySummary> = emptyList(),
  val rows: List<BagDayRow> = emptyList(),
  val status: String = "",
  val loading: Boolean = false,
  val hasPack: Boolean = false
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val repo = SQLiteRepo(ctx)
  private val r2 = R2Client(ctx)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  private fun metaFile(): File = File(PackPaths.packDir(ctx), "remote_meta.json")

  private fun readLocalEtag(): String? {
    return try {
      val f = metaFile()
      if (!f.exists()) return null
      val j = JSONObject(f.readText())
      j.optString("etag", "").takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
      null
    }
  }

  private fun writeLocalMeta(meta: RemotePackMeta) {
    try {
      val f = metaFile()
      if (!f.parentFile.exists()) f.parentFile.mkdirs()
      val j = JSONObject()
      if (!meta.etag.isNullOrBlank()) j.put("etag", meta.etag)
      if (!meta.lastModified.isNullOrBlank()) j.put("lastModified", meta.lastModified)
      if (meta.contentLength != null) j.put("contentLength", meta.contentLength)
      f.writeText(j.toString())
    } catch (_: Throwable) { }
  }

  fun init() {
    val has = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
    _state.value = _state.value.copy(hasPack = has)

    // auto sync on start
    viewModelScope.launch(Dispatchers.IO) {
      syncIfChangedInternal(forceDownloadIfMissing = true)
    }
  }

  fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) { syncIfChangedInternal(forceDownloadIfMissing = false) }
  }

  private suspend fun syncIfChangedInternal(forceDownloadIfMissing: Boolean) {
    try {
      val has = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
      _state.value = _state.value.copy(hasPack = has, loading = true, status = "Checking server…")

      val remote = r2.headPack()
      val localEtag = readLocalEtag()

      val needDownload =
        (!has && forceDownloadIfMissing) ||
        (remote.etag != null && remote.etag != localEtag)

      if (!needDownload) {
        _state.value = _state.value.copy(loading = false, status = "Up to date")
        refreshCurrentScreen()
        return
      }

      _state.value = _state.value.copy(status = "Downloading…")
      val bytes = r2.downloadPackZip()
      ZipUtil.unzipToDir(bytes, PackPaths.packDir(ctx))
      ManifestUtil.ensureExists(PackPaths.manifestFile(ctx))

      writeLocalMeta(remote)

      val hasNow = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
      _state.value = _state.value.copy(hasPack = hasNow, loading = false, status = "Updated")
      refreshCurrentScreen()
    } catch (t: Throwable) {
      _state.value = _state.value.copy(loading = false, status = "Sync error: ${t.message}")
    }
  }

  private fun refreshCurrentScreen() {
    when (_state.value.mode) {
      is ScreenMode.Timeline -> refreshTimeline()
      is ScreenMode.Details -> refreshDetails()
    }
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
