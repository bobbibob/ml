package com.ml.app.domain

object ProfitCalc {
  fun feePct(type: CardType): Double = when (type) {
    CardType.CLASSIC -> 24.0
    CardType.PREMIUM -> 29.0
  }

  // (price - price/100*feePct - 19 - cogs) * orders - spend
  fun netProfit(
    type: CardType,
    orders: Double,
    price: Double,
    spend: Double,
    cogs: Double,
    fixedCost: Double = 19.0
  ): Double {
    val pct = feePct(type)
    val perOrder = price - (price * pct / 100.0) - fixedCost - cogs
    return perOrder * orders - spend
  }
}
