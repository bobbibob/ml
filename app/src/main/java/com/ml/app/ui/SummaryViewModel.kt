package com.ml.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.CardTypeStore
import com.ml.app.data.PackDbSync
import com.ml.app.data.PackPaths
import com.ml.app.data.PackUploadManager
import com.ml.app.data.R2Client
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.CardType
import com.ml.app.domain.DaySummary
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class ScreenMode {
  data object Timeline : ScreenMode()
  data object Stocks : ScreenMode()
  data class Details(val date: LocalDate) : ScreenMode()
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

      if (has) {
        kotlin.runCatching {
          PackDbSync.refreshMergedDb(ctx)
        }
      }

      syncIfChanged()

      if (_state.value.hasPack) {
        refreshTimeline()
      }
    }
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Loading…",
          mode = ScreenMode.Timeline
        )

        val t = repo.loadTimeline(limitDays = 180)
        val ids = t.flatMap { it.byBags }.map { it.bagId }.distinct()
        val types = typeStore.getTypes(ids)

        _state.value = _state.value.copy(
          timeline = t,
          cardTypes = types,
          loading = false,
          status = "OK"
        )
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = "Error: ${t.message}"
        )
      }
    }
  }

  fun openDetails(date: LocalDate) {
    _state.value = _state.value.copy(
      selectedDate = date,
      mode = ScreenMode.Details(date)
    )
    refreshDetails()
  }

  fun backToTimeline() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun openArticleEditor(bagId: String? = null) {
    _state.value = _state.value.copy(mode = ScreenMode.ArticleEditor(bagId))
  }

  fun backFromArticleEditor() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun openStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Stocks)
  }

  fun backFromStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun syncPackNow() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Синхронизация pack…"
        )

        PackUploadManager.saveUserChangesAndUpload(ctx)

        _state.value = _state.value.copy(
          loading = false,
          status = "Pack синхронизирован"
        )

        refreshAfterSync()
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = when (t.message?.trim()) {
            "Сначала обнови пакет" -> "Сначала обнови пакет"
            else -> "Ошибка sync pack: ${t.message}"
          }
        )
      }
    }
  }

  fun setDateFromPicker(date: LocalDate) {
    openDetails(date)
  }

  fun refreshDetails() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Loading…"
        )

        
        val date = _state.value.selectedDate.toString()
        val rows = repo.loadForDate(date)

        val resolved = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }

        val rowsWithResolvedStock = rows.map { row ->
            val stocks = resolved[row.bagId]
                ?.map { com.ml.app.domain.ColorValue(it.color, it.stock) }
                ?: row.stockByColors

            row.copy(
                stockByColors = stocks
            )
        }

        val ids = rowsWithResolvedStock.map { it.bagId }.distinct()
        val types = typeStore.getTypes(ids)

        _state.value = _state.value.copy(
          rows = rowsWithResolvedStock,
          cardTypes = types,
          loading = false,
          status = "OK"
        )
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = "Error: ${t.message}"
        )
      }
    }
  }

  fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Checking updates…"
        )

        val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()

        val localManifestFile = PackPaths.manifestFile(ctx)
        val localVersion = if (localManifestFile.exists()) {
          kotlin.runCatching {
            JSONObject(localManifestFile.readText()).optInt("version", 0)
          }.getOrDefault(0)
        } else {
          0
        }

        val tmpDir = File(ctx.cacheDir, "pack_refresh_check")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val zip = r2.downloadPackZip()
        ZipUtil.unzipToDir(zip, tmpDir)

        val remoteManifestFile = File(tmpDir, "manifest.json")
        val remoteVersion = if (remoteManifestFile.exists()) {
          kotlin.runCatching {
            JSONObject(remoteManifestFile.readText()).optInt("version", 0)
          }.getOrDefault(0)
        } else {
          0
        }

        if (!hasLocal) {
          _state.value = _state.value.copy(status = "Downloading…")

          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.mergedDbFile(ctx).delete()
          PackDbSync.refreshMergedDb(ctx)

          _state.value = _state.value.copy(
            hasPack = true,
            loading = false,
            status = "Downloaded"
          )
          refreshAfterSync()
          return@launch
        }

        if (remoteVersion > localVersion) {
          _state.value = _state.value.copy(status = "Updating…")

          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.mergedDbFile(ctx).delete()
          PackDbSync.refreshMergedDb(ctx)

          _state.value = _state.value.copy(
            hasPack = true,
            loading = false,
            status = "Updated"
          )
          refreshAfterSync()
        } else {
          _state.value = _state.value.copy(
            loading = false,
            status = "No changes"
          )
          refreshAfterSync()
        }
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = "Sync error: ${t.message}"
        )
      }
    }
  }

  private fun refreshAfterSync() {
    when (state.value.mode) {
      is ScreenMode.Timeline -> refreshTimeline()
      is ScreenMode.Details -> refreshDetails()
      is ScreenMode.Stocks -> refreshTimeline()
      is ScreenMode.ArticleEditor -> {
        // stay on editor
      }
    }
  }
}
