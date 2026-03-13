package com.ml.app.domain

data class ColorValue(val color: String, val value: Double)

data class AdsMetrics(
  val spend: Double = 0.0,
  val impressions: Long = 0,
  val clicks: Long = 0,
  val ctr: Double = 0.0, // 0..1
  val cpc: Double = 0.0
)

data class BagOrdersSummary(
  val bagId: String,
  val bagName: String,
  val orders: Int,
  val imagePath: String? = null,

  // for timeline breakdown
  val spend: Double = 0.0,
  val price: Double? = null,
  val cogs: Double = 0.0
)

data class DaySummary(
  val date: String,
  val totalOrders: Int,
  val byBags: List<BagOrdersSummary> = emptyList()
)

data class BagDayRow(
  val bagId: String,
  val bagName: String,
  val price: Double? = null,
  val hypothesis: String? = null,
  val imagePath: String? = null,

  val totalOrders: Double = 0.0,
  val totalSpend: Double = 0.0,
  val cpo: Double = 0.0,

  val cogs: Double = 0.0,
  val deliveryFee: Double? = null,

  val ordersByColors: List<ColorValue> = emptyList(),
  val stockByColors: List<ColorValue> = emptyList(),

  val rk: AdsMetrics = AdsMetrics(),
  val ig: AdsMetrics = AdsMetrics(),
  val totalAds: AdsMetrics = AdsMetrics()
)
