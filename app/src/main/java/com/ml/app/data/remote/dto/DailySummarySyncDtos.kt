package com.ml.app.data.remote.dto

data class DailySummaryEntryDto(
    val entry_id: String? = null,
    val summary_date: String,
    val bag_id: String,
    val color: String,
    val orders: Int,
    val rk_enabled: Boolean = false,
    val rk_spend: Double? = null,
    val rk_impressions: Long? = null,
    val rk_clicks: Long? = null,
    val rk_stake: Double? = null,
    val ig_enabled: Boolean = false,
    val ig_spend: Double? = null,
    val ig_impressions: Long? = null,
    val ig_clicks: Long? = null,
    val price: Double? = null,
    val cogs: Double? = null,
    val delivery_fee: Double? = null,
    val hypothesis: String? = null,
    val created_by_user_id: String? = null,
    val updated_by_user_id: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class DailySummaryUpsertItemDto(
    val bag_id: String,
    val color: String,
    val orders: Int,
    val rk_enabled: Boolean = false,
    val rk_spend: Double? = null,
    val rk_impressions: Long? = null,
    val rk_clicks: Long? = null,
    val rk_stake: Double? = null,
    val ig_enabled: Boolean = false,
    val ig_spend: Double? = null,
    val ig_impressions: Long? = null,
    val ig_clicks: Long? = null,
    val price: Double? = null,
    val cogs: Double? = null,
    val delivery_fee: Double? = null,
    val hypothesis: String? = null
)

data class DailySummaryUpsertRequest(
    val summary_date: String,
    val entries: List<DailySummaryUpsertItemDto>
)

data class DailySummaryUpsertResponse(
    val ok: Boolean,
    val summary_date: String? = null,
    val count: Int? = null,
    val error: String? = null
)

data class DailySummaryByDateResponse(
    val ok: Boolean,
    val summary_date: String? = null,
    val entries: List<DailySummaryEntryDto> = emptyList(),
    val error: String? = null
)
