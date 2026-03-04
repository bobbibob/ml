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

  private fun packFile(rel: String?): String? {
    if (rel.isNullOrBlank()) return null
    val f = File(PackPaths.packDir(context), rel)
    return if (f.exists()) f.absolutePath else null
  }

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)

      val days = ArrayList<DaySummary>()
      db.rawQuery(
        """
          SELECT date,
                 bag_id,
                 MAX(bag_name) AS bag_name,
                 SUM(COALESCE(orders,0)) AS orders
          FROM svodka
          WHERE date IS NOT NULL AND date != '' AND bag_id IS NOT NULL AND bag_id != ''
          GROUP BY date, bag_id
          ORDER BY date DESC, orders DESC
        """.trimIndent(),
        null
      ).use { c ->
        val iDate = c.getColumnIndexOrThrow("date")
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iOrders = c.getColumnIndexOrThrow("orders")

        var curDate: String? = null
        var curTotal = 0
        var curList = ArrayList<BagOrdersSummary>()

        fun flush() {
          val d = curDate ?: return
          days.add(DaySummary(date = d, totalOrders = curTotal, byBags = curList))
        }

        while (c.moveToNext()) {
          val date = c.getString(iDate)
          if (curDate != null && date != curDate) {
            flush()
            curTotal = 0
            curList = ArrayList()
          }
          curDate = date

          val bagId = c.getString(iBagId)
          val bagName = c.getString(iBagName)
          val orders = c.getInt(iOrders)
          curTotal += orders
          curList.add(
            BagOrdersSummary(
              bagId = bagId,
              bagName = bagName,
              orders = orders,
              imagePath = images[bagId]
            )
          )
        }
        if (curDate != null) flush()
      }

      days.take(limitDays)
    }
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)

      // base rows per bag
      val out = ArrayList<BagDayRow>()
      db.rawQuery(
        """
          SELECT bag_id,
                 MAX(bag_name) AS bag_name,
                 MAX(price) AS price,
                 MAX(hypothesis) AS hypothesis,
                 SUM(COALESCE(orders,0)) AS orders,
                 SUM(COALESCE(spend,0)) AS spend,
                 SUM(COALESCE(impressions,0)) AS impressions,
                 SUM(COALESCE(clicks,0)) AS clicks
          FROM svodka
          WHERE date=? AND bag_id IS NOT NULL AND bag_id != ''
          GROUP BY bag_id
          ORDER BY orders DESC
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iHyp = c.getColumnIndexOrThrow("hypothesis")
        val iOrders = c.getColumnIndexOrThrow("orders")
        val iSpend = c.getColumnIndexOrThrow("spend")
        val iImpr = c.getColumnIndexOrThrow("impressions")
        val iClicks = c.getColumnIndexOrThrow("clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iId)
          val bagName = c.getString(iName)
          val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          val hyp = if (c.isNull(iHyp)) null else c.getString(iHyp)

          val totalOrders = c.getDouble(iOrders)
          val totalSpend = c.getDouble(iSpend)
          val impressions = c.getLong(iImpr)
          val clicks = c.getLong(iClicks)

          val ctr = if (impressions > 0) clicks.toDouble() / impressions.toDouble() else 0.0
          val cpc = if (clicks > 0) totalSpend / clicks.toDouble() else 0.0

          val totalAds = AdsMetrics(
            spend = totalSpend,
            impressions = impressions,
            clicks = clicks,
            ctr = ctr,
            cpc = cpc
          )
          val cpo = if (totalOrders > 0) totalSpend / totalOrders else 0.0

          out.add(
            BagDayRow(
              bagId = bagId,
              bagName = bagName,
              price = price,
              hypothesis = hyp,
              imagePath = images[bagId],
              totalOrders = totalOrders,
              totalSpend = totalSpend,
              cpo = cpo,
              ordersByColors = queryOrdersByColors(db, date, bagId),
              stockByColors = queryStockByColors(db, date, bagId),
              rk = queryAds(db, date, bagId, "rk"),
              ig = queryAds(db, date, bagId, "ig"),
              totalAds = totalAds
            )
          )
        }
      }
      out
    }
  }

  fun queryImagesByBagId(db: SQLiteDatabase): Map<String, String?> {
    val out = HashMap<String, String?>()
    db.rawQuery(
      """
      SELECT entity_key, COALESCE(thumbnail_path, image_path) AS p
      FROM media
      WHERE entity_type='bag'
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

  fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val out = ArrayList<ColorValue>()
    db.rawQuery(
      """
        SELECT color, SUM(COALESCE(orders,0)) AS v
        FROM svodka
        WHERE date=? AND bag_id=? AND color IS NOT NULL AND color != '' AND color NOT IN ('__TOTAL__','TOTAL')
        GROUP BY color
        ORDER BY v DESC
      """.trimIndent(),
      arrayOf(date, bagId)
    ).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) out.add(ColorValue(c.getString(ic), c.getDouble(iv)))
    }
    return out
  }

  fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val out = ArrayList<ColorValue>()
    db.rawQuery(
      """
        SELECT color, SUM(COALESCE(stock,0)) AS v
        FROM svodka
        WHERE date=? AND bag_id=? AND color IS NOT NULL AND color != '' AND color NOT IN ('__TOTAL__','TOTAL')
        GROUP BY color
        ORDER BY v DESC
      """.trimIndent(),
      arrayOf(date, bagId)
    ).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) out.add(ColorValue(c.getString(ic), c.getDouble(iv)))
    }
    return out
  }

  fun queryAds(db: SQLiteDatabase, date: String, bagId: String, channel: String): AdsMetrics {
    var spend = 0.0
    var impressions = 0L
    var clicks = 0L

    db.rawQuery(
      """
        SELECT SUM(COALESCE(spend,0)) AS spend,
               SUM(COALESCE(impressions,0)) AS impressions,
               SUM(COALESCE(clicks,0)) AS clicks
        FROM svodka
        WHERE date=? AND bag_id=? AND channel=?
      """.trimIndent(),
      arrayOf(date, bagId, channel)
    ).use { c ->
      if (c.moveToFirst()) {
        spend = c.getDouble(c.getColumnIndexOrThrow("spend"))
        impressions = c.getLong(c.getColumnIndexOrThrow("impressions"))
        clicks = c.getLong(c.getColumnIndexOrThrow("clicks"))
      }
    }

    val ctr = if (impressions > 0) clicks.toDouble() / impressions.toDouble() else 0.0
    val cpc = if (clicks > 0) spend / clicks.toDouble() else 0.0

    return AdsMetrics(spend = spend, impressions = impressions, clicks = clicks, ctr = ctr, cpc = cpc)
  }

  suspend fun fmtMoney(v: Double): String = withContext(Dispatchers.Default) {
    val r = (v * 100.0).roundToInt() / 100.0
    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }
}
