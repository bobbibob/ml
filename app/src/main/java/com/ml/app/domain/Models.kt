package com.ml.app.domain

data class BagDayRow(
  val bag: String,
  val price: Double?,
  val stock: Double?,
  val hypothesis: String?,
  val totalOrders: Double,
  val byColors: List<ColorOrders>,
  val bySources: List<SourceOrders>,
  val imagePath: String?
)

data class ColorOrders(val color: String, val orders: Double)
data class SourceOrders(val source: String, val orders: Double)
