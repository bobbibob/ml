package com.ml.app.domain

data class ColorValue(val color: String, val value: Double)

data class AdsMetrics(
  val spend: Double = 0.0,
  val impressions: Long = 0,
  val clicks: Long = 0,
  val ctr: Double = 0.0,
  val cpc: Double = 0.0
)

data class BagDayRow(
  val bag: String,
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
