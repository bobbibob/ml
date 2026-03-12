package com.ml.app.domain

object ProfitCalc {
  private const val MARKETPLACE_PCT = 24.0
  private const val FF_COST = 3.0
  private const val DEFAULT_DELIVERY_FEE = 6.75

  fun netProfit(
    orders: Double,
    price: Double,
    spend: Double,
    cogs: Double,
    deliveryFee: Double = DEFAULT_DELIVERY_FEE
  ): Double {
    val perOrder = price - (price * MARKETPLACE_PCT / 100.0) - FF_COST - deliveryFee - cogs
    return perOrder * orders - spend
  }
}
