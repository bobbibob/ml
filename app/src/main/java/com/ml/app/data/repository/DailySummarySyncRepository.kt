package com.ml.app.data.repository

import android.content.Context
import com.ml.app.core.network.safeApiCall
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.api.MlApiService
import com.ml.app.data.remote.dto.DailySummaryEntryDto
import com.ml.app.data.remote.dto.DailySummaryUpsertItemDto
import com.ml.app.data.remote.dto.DailySummaryUpsertRequest
import com.ml.app.data.SQLiteRepo

class DailySummarySyncRepository(
    private val api: MlApiService,
    private val context: Context
) {
    suspend fun upsertDailySummary(
        date: String,
        bags: List<SQLiteRepo.DailySummaryBagSave>
    ): AppResult<Unit> {
        val db = SQLiteRepo(context)

        val entries = bags.flatMap { bag ->
            bag.ordersByColor.map { (color, orders) ->
                val snap = kotlinx.coroutines.runBlocking {
                    db.getLatestSnapshotForBagColor(bag.bagId, color)
                }
                DailySummaryUpsertItemDto(
                    bag_id = bag.bagId,
                    color = color,
                    orders = orders,
                    rk_enabled = bag.rkEnabled,
                    rk_spend = bag.rkSpend,
                    rk_impressions = bag.rkImpressions,
                    rk_clicks = bag.rkClicks,
                    rk_stake = bag.rkStake,
                    ig_enabled = bag.igEnabled,
                    ig_spend = bag.igSpend,
                    ig_impressions = bag.igImpressions,
                    ig_clicks = bag.igClicks,
                    price = snap?.price,
                    cogs = snap?.cogs,
                    delivery_fee = snap?.deliveryFee,
                    hypothesis = snap?.hypothesis
                )
            }
        }

        return when (val result = safeApiCall {
            api.dailySummaryUpsert(
                DailySummaryUpsertRequest(
                    summary_date = date,
                    entries = entries
                )
            )
        }) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                if (result.data.ok) AppResult.Success(Unit)
                else AppResult.Error(result.data.error ?: "Failed to sync daily summary")
            }
        }
    }

    suspend fun getDailySummaryByDate(date: String): AppResult<List<DailySummaryEntryDto>> {
        return when (val result = safeApiCall { api.getDailySummaryByDate(date) }) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                if (result.data.ok) AppResult.Success(result.data.entries)
                else AppResult.Error(result.data.error ?: "Failed to load daily summary")
            }
        }
    }

    suspend fun getRecentSummaryDates(limit: Int = 30): AppResult<List<String>> {
        return when (val result = safeApiCall { api.getDailySummaryRecentDates(limit) }) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                if (result.data.ok) AppResult.Success(result.data.dates.map { it.summary_date })
                else AppResult.Error(result.data.error ?: "Failed to load recent dates")
            }
        }
    }
}
