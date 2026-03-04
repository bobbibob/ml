package com.ml.app.domain

data class ColorValue(val color: String, val value: Double)

data class AdsMetrics(
  val spend: Double = 0.0,
  val impressions: Long = 0,
  val clicks: Long = 0,
  val ctr: Double = 0.0,
  val cpc: Double = 0.0
)

data class BagOrdersSummary(
  val bagId: String,
  val bagName: String,
  val orders: Int,
  val imagePath: String?
)

data class DaySummary(
  val date: String,
  val totalOrders: Int,
  val byBags: List<BagOrdersSummary>
)

data class BagDayRow(
  val bagId: String,
  val bagName: String,
  val price: Double?,
  val hypothesis: String?,
  val imagePath: String?,

  // header stats
  val totalOrders: Double,
  val totalSpend: Double,
  val cpo: Double,

  // breakdowns
  val ordersByColors: List<ColorValue>,
  val stockByColors: List<ColorValue>,

  // ads
  val rk: AdsMetrics,
  val ig: AdsMetrics,
  val totalAds: AdsMetrics
)
