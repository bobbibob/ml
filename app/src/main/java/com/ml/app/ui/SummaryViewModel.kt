package com.ml.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.PackPaths
import com.ml.app.data.R2Client
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.ZipUtil
import com.ml.app.domain.BagShort
import com.ml.app.domain.BagSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SummaryState(
  val loading: Boolean = false,
  val status: String = "",
  val hasPack: Boolean = false,
  val dates: List<String> = emptyList(),
  val selectedDate: String? = null,
  val bags: List<BagShort> = emptyList(),
  val selectedBagId: String? = null,
  val summary: BagSummary? = null
)

class SummaryViewModel(
  private val ctx: Context,
  private val prefs: SharedPreferences
) : ViewModel() {

  private val r2 = R2Client(ctx)
  private val repo = SQLiteRepo(ctx)

  private val _state = MutableStateFlow(SummaryState())
  val state: StateFlow<SummaryState> = _state

  fun init() {
    viewModelScope.launch(Dispatchers.IO) {
      val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
      _state.value = _state.value.copy(hasPack = hasLocal)
      if (hasLocal) {
        loadDates()
      }
    }
  }

  fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Checking updates…")

        val remote = r2.headPack()
        val remoteEtag = remote.etag?.trim()?.trim('"') ?: ""

        // Some proxies/CDNs may not return ETag consistently.
        // Fallback to Last-Modified / Content-Length so updates still work.
        val remoteToken = when {
          remoteEtag.isNotBlank() -> "etag:$remoteEtag"
          !remote.lastModified.isNullOrBlank() -> "lm:${remote.lastModified}"
          remote.contentLength != null -> "len:${remote.contentLength}"
          else -> ""
        }

        val legacyLocalEtag = prefs.getString("etag", "") ?: ""
        val localToken = prefs.getString("pack_token", "")?.ifBlank {
          if (legacyLocalEtag.isNotBlank()) "etag:$legacyLocalEtag" else ""
        } ?: ""

        val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()

        if (!hasLocal) {
          // first install: must download
          _state.value = _state.value.copy(status = "Downloading…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          prefs.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Downloaded")
          // refresh current screen
          refreshAfterSync()
          return@launch
        }

        if (remoteToken.isNotBlank() && remoteToken != localToken) {
          _state.value = _state.value.copy(status = "Updating…")
          val zip = r2.downloadPackZip()
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          prefs.edit().putString("etag", remoteEtag).putString("pack_token", remoteToken).apply()
          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Updated")
          refreshAfterSync()
        } else {
          _state.value = _state.value.copy(loading = false, status = "No changes")
          // still refresh visible screen (fast)
          refreshAfterSync()
        }
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Sync error: ${t.message ?: t.javaClass.simpleName}")
      }
    }
  }

  private fun refreshAfterSync() {
    viewModelScope.launch(Dispatchers.IO) {
      loadDates()
      val d = _state.value.selectedDate
      val b = _state.value.selectedBagId
      if (!d.isNullOrBlank()) {
        loadBags(d)
        if (!b.isNullOrBlank()) loadSummary(d, b)
      }
    }
  }

  private suspend fun loadDates() {
    val dates = repo.queryDates()
    val selected = _state.value.selectedDate ?: dates.firstOrNull()
    _state.value = _state.value.copy(dates = dates, selectedDate = selected)
    if (!selected.isNullOrBlank()) loadBags(selected)
  }

  fun selectDate(date: String) {
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = _state.value.copy(selectedDate = date, selectedBagId = null, summary = null)
      loadBags(date)
    }
  }

  private suspend fun loadBags(date: String) {
    val bags = repo.queryBagsForDate(date)
    val selected = _state.value.selectedBagId ?: bags.firstOrNull()?.id
    _state.value = _state.value.copy(bags = bags, selectedBagId = selected)
    if (!selected.isNullOrBlank()) loadSummary(date, selected)
  }

  fun selectBag(bagId: String) {
    val date = _state.value.selectedDate ?: return
    viewModelScope.launch(Dispatchers.IO) {
      _state.value = _state.value.copy(selectedBagId = bagId)
      loadSummary(date, bagId)
    }
  }

  private suspend fun loadSummary(date: String, bagId: String) {
    try {
      _state.value = _state.value.copy(loading = true, status = "Loading…")
      val s = repo.querySummary(date, bagId)
      _state.value = _state.value.copy(summary = s, loading = false, status = "")
    } catch (t: Throwable) {
      _state.value = _state.value.copy(loading = false, status = "DB error: ${t.message ?: t.javaClass.simpleName}")
    }
  }
}
