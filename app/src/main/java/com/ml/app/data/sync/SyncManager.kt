package com.ml.app.data.sync

import android.content.Context
import com.ml.app.core.network.ApiModule
import com.ml.app.core.result.AppResult
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.remote.api.MlApiService
import com.ml.app.data.repository.DailySummarySyncRepository
import com.ml.app.data.session.PrefsSessionStorage
import java.time.LocalDate

class SyncManager(
    private val ctx: Context
) {
    private val repo = SQLiteRepo(ctx)
    private val session = PrefsSessionStorage(ctx)

    private fun api(): MlApiService {
        return ApiModule.createApi(
            baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
            sessionStorage = session
        )
    }

    suspend fun pushPendingDailySummary() {
        if (session.getToken().isNullOrBlank()) return

        val syncRepo = DailySummarySyncRepository(api(), ctx)
        val pending = repo.getPendingDailySummarySyncItems()

        for (item in pending) {
            when (val res = syncRepo.upsertDailySummary(item.summaryDate, item.bags)) {
                is AppResult.Success -> repo.markDailySummarySyncSuccess(item.id)
                is AppResult.Error -> repo.markDailySummarySyncError(item.id, res.message)
            }
        }
    }

    suspend fun pullDailySummary(date: String) {
        if (session.getToken().isNullOrBlank()) return

        val syncRepo = DailySummarySyncRepository(api(), ctx)
        when (val res = syncRepo.getDailySummaryByDate(date)) {
            is AppResult.Success -> repo.applyRemoteDailySummary(date, res.data)
            is AppResult.Error -> {}
        }
    }

    suspend fun pullRecentDailySummaries(limit: Int = 30) {
        if (session.getToken().isNullOrBlank()) return

        val syncRepo = DailySummarySyncRepository(api(), ctx)
        when (val res = syncRepo.getRecentSummaryDates(limit)) {
            is AppResult.Success -> {
                for (date in res.data) {
                    pullDailySummary(date)
                }
            }
            is AppResult.Error -> {}
        }
    }

    suspend fun fullSync(selectedDate: LocalDate?) {
        pushPendingDailySummary()
        pullRecentDailySummaries(30)
        selectedDate?.let { pullDailySummary(it.toString()) }
    }
}
