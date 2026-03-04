package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class SQLiteRepo(private val context: Context) {

  private fun openDb(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun normalizeColor(raw: String?, bag: String?): String? {
    if (raw == null) return null
    val s = raw.trim()
    if (s.isEmpty()) return null

    val low = s.lowercase()
    if (low == "__total__" || low == "total") return null
    if (low.contains("#")) return null
    if (low.contains("div/")) return null
    if (low.matches(Regex("-?\\d+(\\.\\d+)?"))) return null

    val bad = listOf(
      "сумка","рюкзак","органика","инста","instagram","внутренняя","рк",
      "гипотеза","цена","заказы","остаток","расход","ставка","показы",
      "клики","ctr","cpc","цвет"
    )
    if (bad.any { low.contains(it) }) return null

    bag?.let { if (low == it.trim().lowercase()) return null }

    if (s.length > 25) return null
    return s
  }

  // ---------- TIMELINE ----------
  suspend fun listDatesDesc(limit: Int = 120): List<String> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val out = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT $limit",
        null
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) out.add(c.getString(i))
      }
      out
    }
  }

  suspend fun loadTimeline(limitDays: Int = 120): List<DaySummary> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val dates = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT $limitDays",
        null
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) dates.add(c.getString(i))
      }

      val out = ArrayList<DaySummary>(dates.size)

      for (date in dates) {
        val byBags = ArrayList<BagOrders>()

        db.rawQuery(
          """
          SELECT bag, CAST(ROUND(COALESCE(orders,0)) AS INTEGER) AS o
          FROM svodka
          WHERE date=? AND (color="__TOTAL__" OR color="TOTAL")
          ORDER BY o DESC, bag ASC
          """.trimIndent(),
          arrayOf(date)
        ).use { c ->
          val ib = c.getColumnIndexOrThrow("bag")
          val io = c.getColumnIndexOrThrow("o")
          while (c.moveToNext()) {
            val bag = c.getString(ib)?.trim() ?: continue
            val o = c.getInt(io)
            byBags.add(BagOrders(bag, o))
          }
        }

        val total = byBags.sumOf { it.orders }
        out.add(DaySummary(date = date, totalOrders = total, byBags = byBags))
      }

      out
    }
  }

  // ---------- DETAILS (как раньше) ----------
  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDb().use { db ->
      val bags = LinkedHashSet<String>()
      db.rawQuery(
        "SELECT DISTINCT bag FROM svodka WHERE date=? ORDER BY bag",
        arrayOf(date)
      ).use { c ->
        val i = c.getColumnIndexOrThrow("bag")
        while (c.moveToNext()) {
          val b = c.getString(i)
          if (!b.isNullOrBlank()) bags.add(b)
        }
      }

      val result = ArrayList<BagDayRow>()
      for (bag in bags) {

        var price: Double? = null
        var hypothesis: String? = null
        var totalOrders = 0.0

        var rk = AdsMetrics()
        var ig = AdsMetrics()

        db.rawQuery(
          """
          SELECT *
          FROM svodka
          WHERE date=? AND bag=? AND (color="__TOTAL__" OR color="TOTAL")
          LIMIT 1
          """.trimIndent(),
          arrayOf(date, bag)
        ).use { c ->
          if (c.moveToFirst()) {
            val ip = c.getColumnIndex("price")
            if (ip >= 0 && !c.isNull(ip)) price = c.getDouble(ip)

            val ih = c.getColumnIndex("hypothesis")
            if (ih >= 0 && !c.isNull(ih)) hypothesis = c.getString(ih)

            val io = c.getColumnIndex("orders")
            if (io >= 0 && !c.isNull(io)) totalOrders = c.getDouble(io).roundToInt().toDouble()

            // если колонок нет — сборка упадёт. Но у нас schema эталонная, так что ок.
            rk = AdsMetrics(
              spend = c.getDouble(c.getColumnIndexOrThrow("rk_spend")),
              impressions = c.getLong(c.getColumnIndexOrThrow("rk_impressions")),
              clicks = c.getLong(c.getColumnIndexOrThrow("rk_clicks")),
              ctr = c.getDouble(c.getColumnIndexOrThrow("rk_ctr")),
              cpc = c.getDouble(c.getColumnIndexOrThrow("rk_cpc"))
            )
            ig = AdsMetrics(
              spend = c.getDouble(c.getColumnIndexOrThrow("ig_spend")),
              impressions = c.getLong(c.getColumnIndexOrThrow("ig_impressions")),
              clicks = c.getLong(c.getColumnIndexOrThrow("ig_clicks")),
              ctr = c.getDouble(c.getColumnIndexOrThrow("ig_ctr")),
              cpc = c.getDouble(c.getColumnIndexOrThrow("ig_cpc"))
            )
          }
        }

        val ordersByColors = mutableListOf<ColorValue>()
        val stockByColors = mutableListOf<ColorValue>()

        db.rawQuery(
          """
          SELECT color, COALESCE(orders,0) AS o, COALESCE(stock,0) AS s
          FROM svodka
          WHERE date=? AND bag=? AND color NOT IN ("__TOTAL__","TOTAL")
          """.trimIndent(),
          arrayOf(date, bag)
        ).use { c ->
          val ic = c.getColumnIndexOrThrow("color")
          val io = c.getColumnIndexOrThrow("o")
          val isx = c.getColumnIndexOrThrow("s")

          while (c.moveToNext()) {
            val color = normalizeColor(c.getString(ic), bag) ?: continue
            val o = c.getDouble(io).roundToInt().toDouble()
            val s = c.getDouble(isx).roundToInt().toDouble()

            if (o > 0) ordersByColors.add(ColorValue(color, o))
            if (s > 0) stockByColors.add(ColorValue(color, s))
          }
        }

        val totalSpend = rk.spend + ig.spend
        val cpo = if (totalOrders > 0) totalSpend / totalOrders else 0.0

        val totalImpr = rk.impressions + ig.impressions
        val totalClicks = rk.clicks + ig.clicks

        val totalAds = AdsMetrics(
          spend = totalSpend,
          impressions = totalImpr,
          clicks = totalClicks,
          ctr = if (totalImpr > 0) totalClicks.toDouble() / totalImpr else 0.0,
          cpc = if (totalClicks > 0) totalSpend / totalClicks else 0.0
        )

        result.add(
          BagDayRow(
            bag = bag,
            price = price,
            hypothesis = hypothesis,
            imagePath = null,
            totalOrders = totalOrders,
            totalSpend = totalSpend,
            cpo = cpo,
            ordersByColors = ordersByColors.sortedByDescending { it.value },
            stockByColors = stockByColors.sortedByDescending { it.value },
            rk = rk,
            ig = ig,
            totalAds = totalAds
          )
        )
      }

      result.sortedByDescending { it.totalOrders }
    }
  }
}
