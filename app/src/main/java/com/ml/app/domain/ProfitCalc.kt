package com.ml.app.domain

object ProfitCalc {
  private const val MARKETPLACE_PCT = 24.0
  private const val FF_COST = 3.0
  const val DEFAULT_DELIVERY_FEE = 7.95

  fun netProfit(
    orders: Double,
    price: Double,
    spend: Double,
    cogs: Double,
    deliveryFee: Double?
  ): Double {
    val delivery = deliveryFee ?: DEFAULT_DELIVERY_FEE
    val perOrder = price - (price * MARKETPLACE_PCT / 100.0) - FF_COST - delivery - cogs
    return perOrder * orders - spend
  }

  fun netProfitBeforeSpend(
    orders: Double,
    price: Double,
    cogs: Double,
    deliveryFee: Double?
  ): Double {
    val delivery = deliveryFee ?: DEFAULT_DELIVERY_FEE
    val perOrder = price - (price * MARKETPLACE_PCT / 100.0) - FF_COST - delivery - cogs
    return perOrder * orders
  }
}
