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
  data object AddDailySummary : ScreenMode()
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


  private fun buildRuntimeDebugInfo(): String {
    return try {
      val packDb = PackPaths.dbFile(ctx)
      val mergedDb = PackDbSync.mergedDbFile(ctx)

      val packExists = packDb.exists()
      val packSize = if (packExists) packDb.length() else -1L
      val mergedExists = mergedDb.exists()
      val mergedSize = if (mergedExists) mergedDb.length() else -1L

      val dbToRead = if (packExists && packSize > 0L) packDb else mergedDb

      if (!dbToRead.exists() || dbToRead.length() <= 0L) {
        return "DBG packExists=$packExists packSize=$packSize mergedExists=$mergedExists mergedSize=$mergedSize NO_DB"
      }

      val db = android.database.sqlite.SQLiteDatabase.openDatabase(
        dbToRead.absolutePath,
        null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
      )

      db.use {
        val svodkaCount = it.rawQuery("SELECT COUNT(*) FROM svodka", null).use { c ->
          if (c.moveToFirst()) c.getInt(0) else -1
        }
        val totalCount = it.rawQuery(
          "SELECT COUNT(*) FROM svodka WHERE color IN ('__TOTAL__','TOTAL')",
          null
        ).use { c ->
          if (c.moveToFirst()) c.getInt(0) else -1
        }
        val maxDate = it.rawQuery("SELECT MAX(date) FROM svodka", null).use { c ->
          if (c.moveToFirst()) c.getString(0) ?: "null" else "null"
        }
        val selected = _state.value.selectedDate.toString()

        "DBG db=${dbToRead.absolutePath} packSize=$packSize mergedSize=$mergedSize svodka=$svodkaCount total=$totalCount maxDate=$maxDate selected=$selected"
      }
    } catch (t: Throwable) {
      "DBG ERROR ${t::class.java.simpleName}: ${t.message}"
    }
  }



  private fun isLocalPackHealthy(): Boolean {
    return try {
      val dbFile = PackPaths.dbFile(ctx)
      if (!dbFile.exists() || dbFile.length() < 1024L * 1024L) return false

      val db = android.database.sqlite.SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
      )

      db.use {
        val svodkaCount = it.rawQuery("SELECT COUNT(*) FROM svodka", null).use { c ->
          if (c.moveToFirst()) c.getInt(0) else 0
        }
        val totalCount = it.rawQuery(
          "SELECT COUNT(*) FROM svodka WHERE color IN ('__TOTAL__','TOTAL')",
          null
        ).use { c ->
          if (c.moveToFirst()) c.getInt(0) else 0
        }
        svodkaCount > 0 && totalCount > 0
      }
    } catch (_: Throwable) {
      false
    }
  }

  private fun clearLocalPack() {
    kotlin.runCatching { PackPaths.packDir(ctx).deleteRecursively() }
    kotlin.runCatching { PackDbSync.mergedDbFile(ctx).delete() }
  }

  private suspend fun installBundledPackIfPresent(): Boolean {
    val resId = ctx.resources.getIdentifier("bootstrap_pack", "raw", ctx.packageName)
    if (resId == 0) return false

    _state.value = _state.value.copy(
      loading = true,
      status = "Installing bundled base…"
    )

    val bytes = ctx.resources.openRawResource(resId).use { it.readBytes() }

    val packDir = PackPaths.packDir(ctx)
    if (!packDir.exists()) packDir.mkdirs()

    ZipUtil.unzipToDir(bytes, packDir)

    val dbFile = PackPaths.dbFile(ctx)
    if (!dbFile.exists() || dbFile.length() == 0L) {
      throw IllegalStateException("bundled data.sqlite not found after unzip: ${dbFile.absolutePath}")
    }

    kotlin.runCatching {
      PackDbSync.mergedDbFile(ctx).delete()
      PackDbSync.refreshMergedDb(ctx)
    }

    _state.value = _state.value.copy(
      hasPack = true,
      loading = false,
      status = "Bundled base installed"
    )

    refreshTimeline()
    return true
  }


  private suspend fun downloadAndInstallPack(statusText: String) {
    _state.value = _state.value.copy(loading = true, status = statusText)

    val packDir = PackPaths.packDir(ctx)
    if (!packDir.exists()) packDir.mkdirs()

    val zip = r2.downloadPackZip()
    ZipUtil.unzipToDir(zip, packDir)

    val dbFile = PackPaths.dbFile(ctx)
    if (!dbFile.exists() || dbFile.length() == 0L) {
      throw IllegalStateException("data.sqlite not found after unzip: ${dbFile.absolutePath}")
    }

    kotlin.runCatching {
      PackDbSync.mergedDbFile(ctx).delete()
      PackDbSync.refreshMergedDb(ctx)
    }

    _state.value = _state.value.copy(
      hasPack = true,
      loading = false,
      status = "Downloaded"
    )

    refreshTimeline()
  }

  fun init() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val hasHealthyLocal = isLocalPackHealthy()
        _state.value = _state.value.copy(hasPack = hasHealthyLocal)

        if (hasHealthyLocal) {
          kotlin.runCatching { PackDbSync.refreshMergedDb(ctx) }
          _state.value = _state.value.copy(status = "Открываем локальную базу…")
          refreshTimeline()
          return@launch
        }

        clearLocalPack()

        val bundledOk = installBundledPackIfPresent()
        if (bundledOk) {
          refreshTimeline()
        } else {
          _state.value = _state.value.copy(
            loading = false,
            hasPack = false,
            status = "Нет локальной базы"
          )
        }
      } catch (t: Throwable) {
        clearLocalPack()
        _state.value = _state.value.copy(
          loading = false,
          hasPack = false,
          status = "Ошибка запуска: ${t.message}"
        )
      }
    }
  }

  fun refreshTimeline() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(
          loading = true,
          status = "Loading summary…"
        )

        val debugBefore = buildRuntimeDebugInfo()
        val t = repo.loadTimeline(limitDays = 180)
        val ids = t.flatMap { it.byBags }.map { it.bagId }.distinct()
        val types = typeStore.getTypes(ids)
        val latestDate = t.firstOrNull()?.date?.let {
          kotlin.runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: _state.value.selectedDate
        val debugAfter = buildRuntimeDebugInfo()

        _state.value = _state.value.copy(
          timeline = t,
          cardTypes = types,
          selectedDate = latestDate,
          loading = false,
          hasPack = true,
          status = "SUMMARY days=${t.size}; before=[$debugBefore]; after=[$debugAfter]"
        )
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = "SUMMARY ERROR: ${t::class.java.simpleName}: ${t.message}; ${buildRuntimeDebugInfo()}"
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

  fun openAddDailySummary() {
    _state.value = _state.value.copy(mode = ScreenMode.AddDailySummary)
  }

  fun backFromAddDailySummary() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
    refreshTimeline()
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
    _state.value = _state.value.copy(status = "Обновление отключено")
  }

  private fun refreshAfterSync() {
    when (state.value.mode) {
      is ScreenMode.Timeline -> refreshTimeline()
      is ScreenMode.Details -> refreshDetails()
      is ScreenMode.Stocks -> {
        _state.value = _state.value.copy(status = "Updated")
      }
      is ScreenMode.AddDailySummary -> {
        _state.value = _state.value.copy(status = "Updated")
      }
      is ScreenMode.ArticleEditor -> {
        _state.value = _state.value.copy(status = "Updated")
      }
    }
  }
}
