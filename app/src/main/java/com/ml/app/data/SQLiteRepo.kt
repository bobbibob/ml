package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class SQLiteRepo(private val context: Context) {

  private fun openDbReadOnly(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun packFile(relative: String?): String? {
    if (relative.isNullOrBlank()) return null
    val f = File(PackPaths.packDir(context), relative)
    return if (f.exists()) f.absolutePath else null
  }

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)

      val dates = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT ?",
        arrayOf(limitDays.toString())
      ).use { c ->
        val id = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) dates.add(c.getString(id))
      }

      val out = ArrayList<DaySummary>()
      for (date in dates) {
        val total = queryTotalOrdersAllBags(db, date)

        val byBags = ArrayList<BagOrdersSummary>()
        db.rawQuery(
          """
          SELECT s.bag_id, COALESCE(b.bag_name, s.bag_id) AS bag_name,
                 CAST(ROUND(SUM(CASE WHEN s.color IN ("__TOTAL__","TOTAL") THEN COALESCE(s.orders,0) ELSE 0 END)) AS INTEGER) AS ord
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.date=?
          GROUP BY s.bag_id, bag_name
          ORDER BY ord DESC, bag_name ASC
          """.trimIndent(),
          arrayOf(date)
        ).use { c ->
          val iId = c.getColumnIndexOrThrow("bag_id")
          val iName = c.getColumnIndexOrThrow("bag_name")
          val iOrd = c.getColumnIndexOrThrow("ord")
          while (c.moveToNext()) {
            val bagId = c.getString(iId)
            val bagName = c.getString(iName)
            val ord = c.getInt(iOrd)
            byBags.add(BagOrdersSummary(bagId, bagName, ord, images[bagId]))
          }
        }

        out.add(DaySummary(date = date, totalOrders = total, byBags = byBags))
      }

      out
    }
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)

      val bags = ArrayList<Pair<String, String>>() // bag_id, bag_name
      db.rawQuery(
        """
        SELECT DISTINCT s.bag_id, COALESCE(b.bag_name, s.bag_id) AS bag_name
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        WHERE s.date=?
        ORDER BY bag_name ASC
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        while (c.moveToNext()) {
          bags.add(c.getString(iId) to c.getString(iName))
        }
      }

      val result = ArrayList<BagDayRow>()
      for ((bagId, bagName) in bags) {
        val base = queryBase(db, date, bagId)
        val ordersByColors = queryOrdersByColors(db, date, bagId)
        val stockByColors = queryStockByColors(db, date, bagId)
        val (rk, ig) = queryAds(db, date, bagId)

        val totalOrders = queryTotalOrders(db, date, bagId).let { tot ->
          if (tot > 0.0) tot else ordersByColors.sumOf { it.value }
        }

        val totalSpend = rk.spend + ig.spend
        val cpo = if (totalOrders > 0.0) totalSpend / totalOrders else 0.0

        val totalImpr = rk.impressions + ig.impressions
        val totalClicks = rk.clicks + ig.clicks
        val totalCtr = if (totalImpr > 0) totalClicks.toDouble() / totalImpr.toDouble() else 0.0
        val totalCpc = if (totalClicks > 0) totalSpend / totalClicks.toDouble() else 0.0

        val totalAds = AdsMetrics(
          spend = totalSpend,
          impressions = totalImpr,
          clicks = totalClicks,
          ctr = totalCtr,
          cpc = totalCpc
        )

        result.add(
          BagDayRow(
            bagId = bagId,
            bagName = bagName,
            price = base.first,
            hypothesis = base.second,
            imagePath = images[bagId],
            totalOrders = totalOrders,
            totalSpend = totalSpend,
            cpo = cpo,
            ordersByColors = ordersByColors,
            stockByColors = stockByColors,
            rk = rk,
            ig = ig,
            totalAds = totalAds
          )
        )
      }

      result.sortedByDescending { it.totalOrders }
    }
  }

  private fun queryImagesByBagId(db: SQLiteDatabase): Map<String, String?> {
    val out = HashMap<String, String?>()
    db.rawQuery(
      """
      SELECT entity_key, COALESCE(thumbnail_path, image_path) AS p
      FROM media
      WHERE entity_type=bag
      """.trimIndent(),
      null
    ).use { c ->
      val iKey = c.getColumnIndexOrThrow("entity_key")
      val iP = c.getColumnIndexOrThrow("p")
      while (c.moveToNext()) {
        val key = c.getString(iKey)
        val rel = c.getString(iP)
        out[key] = packFile(rel)
      }
    }
    return out
  }

  private fun queryBase(db: SQLiteDatabase, date: String, bagId: String): Pair<Double?, String?> {
    val sql = """
      SELECT MAX(price) AS price, MAX(hypothesis) AS hypothesis
      FROM svodka
      WHERE date=? AND bag_id=?
    """.trimIndent()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      return if (c.moveToFirst()) {
        val ip = c.getColumnIndexOrThrow("price")
        val ih = c.getColumnIndexOrThrow("hypothesis")
        val price = if (c.isNull(ip)) null else c.getDouble(ip)
        val hyp = if (c.isNull(ih)) null else c.getString(ih)
        price to hyp
      } else null to null
    }
  }

  private fun queryTotalOrdersAllBags(db: SQLiteDatabase, date: String): Int {
    val sql = """
      SELECT CAST(ROUND(SUM(COALESCE(orders,0))) AS INTEGER) AS v
      FROM svodka
      WHERE date=? AND color IN ("__TOTAL__","TOTAL")
    """.trimIndent()
    db.rawQuery(sql, arrayOf(date)).use { c ->
      return if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow("v")) else 0
    }
  }

  private fun queryTotalOrders(db: SQLiteDatabase, date: String, bagId: String): Double {
    val sql = """
      SELECT SUM(COALESCE(orders,0)) AS v
      FROM svodka
      WHERE date=? AND bag_id=? AND color IN ("__TOTAL__","TOTAL")
      LIMIT 1
    """.trimIndent()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      return if (c.moveToFirst()) c.getDouble(c.getColumnIndexOrThrow("v")) else 0.0
    }
  }

  private fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT s.color, SUM(COALESCE(s.orders,0)) AS v
      FROM svodka s
      JOIN colors c ON c.color = s.color
      WHERE s.date=? AND s.bag_id=? AND s.color NOT IN ("__TOTAL__","TOTAL")
      GROUP BY s.color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) {
        val color = c.getString(ic)
        val v = c.getDouble(iv)
        if (v <= 0.0) continue
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  private fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT s.color, MAX(COALESCE(s.stock,0)) AS v
      FROM svodka s
      JOIN colors c ON c.color = s.color
      WHERE s.date=? AND s.bag_id=? AND s.color NOT IN ("__TOTAL__","TOTAL")
      GROUP BY s.color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) {
        val color = c.getString(ic)
        val v = c.getDouble(iv)
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  private fun queryAds(db: SQLiteDatabase, date: String, bagId: String): Pair<AdsMetrics, AdsMetrics> {
    val sql = """
      SELECT
        rk_spend, rk_impressions, rk_clicks,
        ig_spend, ig_impressions, ig_clicks
      FROM svodka
      WHERE date=? AND bag_id=? AND color IN ("__TOTAL__","TOTAL")
      ORDER BY CASE WHEN color="__TOTAL__" THEN 0 ELSE 1 END
      LIMIT 1
    """.trimIndent()

    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      if (!c.moveToFirst()) return AdsMetrics() to AdsMetrics()

      val rkSpend = c.getDouble(0)
      val rkImpr = c.getDouble(1).roundToInt().toLong()
      val rkClicks = c.getDouble(2).roundToInt().toLong()

      val igSpend = c.getDouble(3)
      val igImpr = c.getDouble(4).roundToInt().toLong()
      val igClicks = c.getDouble(5).roundToInt().toLong()

      fun ctr(clicks: Long, impr: Long): Double = if (impr > 0) clicks.toDouble() / impr.toDouble() else 0.0
      fun cpc(spend: Double, clicks: Long): Double = if (clicks > 0) spend / clicks.toDouble() else 0.0

      val rk = AdsMetrics(
        spend = rkSpend,
        impressions = rkImpr,
        clicks = rkClicks,
        ctr = ctr(rkClicks, rkImpr),
        cpc = cpc(rkSpend, rkClicks)
      )
      val ig = AdsMetrics(
        spend = igSpend,
        impressions = igImpr,
        clicks = igClicks,
        ctr = ctr(igClicks, igImpr),
        cpc = cpc(igSpend, igClicks)
      )
      return rk to ig
    }
  }
}
