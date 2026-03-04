package com.ml.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.BuildConfig
import com.ml.app.data.ManifestUtil
import com.ml.app.data.PackPaths
import com.ml.app.data.R2Client
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
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
  private val r2 = R2Client(ctx)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  fun init() {
    val has = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
    _state.value = _state.value.copy(hasPack = has)

    // AUTO: если нет пакета — качаем сразу
    if (!has) {
      syncDownloadAndOpenTimeline()
    } else {
      refreshTimeline()
    }
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Loading timeline…",
          mode = ScreenMode.Timeline
        )
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

  fun refreshPack() {
    // в UI кнопка "Обновить" сверху — перекачать pack и обновить таймлайн
    syncDownloadAndOpenTimeline()
  }

  private fun syncDownloadAndOpenTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Downloading pack…")

        val bytes = r2.downloadPackZip()
        ZipUtil.unzipToDir(bytes, PackPaths.packDir(ctx))

        // safety: ensure manifest exists
        ManifestUtil.ensureExists(PackPaths.manifestFile(ctx))

        val has = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
        _state.value = _state.value.copy(hasPack = has, loading = false, status = "Downloaded")
        if (has) refreshTimeline()
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Download error: ${t.message}")
      }
    }
  }

  // если позже нужно "Сохранить" обратно в R2 (конфликт-чек) — вернем.
  fun uploadWithConflictCheck() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val packDir = PackPaths.packDir(ctx)
        val localManifest = PackPaths.manifestFile(ctx)
        ManifestUtil.ensureExists(localManifest)
        val localVersion = ManifestUtil.readVersion(localManifest)

        _state.value = _state.value.copy(loading = true, status = "Checking remote…")
        val remoteZip = r2.downloadPackZip()
        val tmp = PackPaths.tempDir(ctx)
        ZipUtil.unzipToDir(remoteZip, tmp)
        val remoteManifest = File(tmp, "manifest.json")
        val remoteVersion = if (remoteManifest.exists()) ManifestUtil.readVersion(remoteManifest) else 0

        if (remoteVersion != localVersion) {
          _state.value = _state.value.copy(
            loading = false,
            status = "CONFLICT: remote=$remoteVersion local=$localVersion. Сначала обнови пакет."
          )
          return@launch
        }

        _state.value = _state.value.copy(status = "Packing…")
        val dbHash = ManifestUtil.computeDbHash(PackPaths.dbFile(ctx))
        val imagesHash = ManifestUtil.computeImagesHash(PackPaths.imagesDir(ctx))
        ManifestUtil.bumpVersionAndUpdate(localManifest, BuildConfig.UPDATED_BY, dbHash, imagesHash)

        val zipBytes = ZipUtil.zipDirToBytes(packDir)
        _state.value = _state.value.copy(status = "Uploading…")
        r2.uploadPackZip(zipBytes)

        _state.value = _state.value.copy(loading = false, status = "Uploaded")
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Upload error: ${t.message}")
      }
    }
  }
}
