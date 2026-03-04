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
      val out = ArrayList<DaySummary>()
      db.rawQuery(
        """
          SELECT date,
                 SUM(COALESCE(orders,0)) AS orders,
                 SUM(COALESCE(spend,0)) AS spend,
                 SUM(COALESCE(impressions,0)) AS impressions,
                 SUM(COALESCE(clicks,0)) AS clicks
          FROM svodka
          WHERE date IS NOT NULL AND date != ''
          GROUP BY date
          ORDER BY date DESC
          LIMIT ?
        """.trimIndent(),
        arrayOf(limitDays.toString())
      ).use { c ->
        val iDate = c.getColumnIndexOrThrow("date")
        val iOrders = c.getColumnIndexOrThrow("orders")
        val iSpend = c.getColumnIndexOrThrow("spend")
        val iImpr = c.getColumnIndexOrThrow("impressions")
        val iClicks = c.getColumnIndexOrThrow("clicks")
        while (c.moveToNext()) {
          val date = c.getString(iDate)
          val orders = c.getInt(iOrders)
          val spend = c.getDouble(iSpend)
          val impressions = c.getLong(iImpr)
          val clicks = c.getLong(iClicks)
          val ctr = if (impressions > 0) clicks.toDouble() / impressions.toDouble() else 0.0
          val cpc = if (clicks > 0) spend / clicks.toDouble() else 0.0
          out.add(
            DaySummary(
              date = date,
              orders = orders,
              ads = AdsMetrics(
                spend = spend,
                impressions = impressions,
                clicks = clicks,
                ctr = ctr,
                cpc = cpc
              )
            )
          )
        }
      }
      out
    }
  }

  suspend fun loadDayDetails(date: String): DayDetails = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      // orders per bag
      val images = queryImagesByBagId(db)
      val bags = ArrayList<BagOrdersSummary>()
      db.rawQuery(
        """
          SELECT bag_id,
                 MAX(bag_name) AS bag_name,
                 SUM(COALESCE(orders,0)) AS orders
          FROM svodka
          WHERE date=? AND bag_id IS NOT NULL AND bag_id != ''
          GROUP BY bag_id
          ORDER BY orders DESC
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        val iOrders = c.getColumnIndexOrThrow("orders")
        while (c.moveToNext()) {
          val id = c.getString(iId)
          val name = c.getString(iName)
          val orders = c.getInt(iOrders)
          val image = images[id]
          bags.add(BagOrdersSummary(id, name, orders, image))
        }
      }

      // total ads metrics for the date
      var spend = 0.0
      var impressions = 0L
      var clicks = 0L
      db.rawQuery(
        """
          SELECT SUM(COALESCE(spend,0)) AS spend,
                 SUM(COALESCE(impressions,0)) AS impressions,
                 SUM(COALESCE(clicks,0)) AS clicks
          FROM svodka
          WHERE date=?
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        if (c.moveToFirst()) {
          spend = c.getDouble(c.getColumnIndexOrThrow("spend"))
          impressions = c.getLong(c.getColumnIndexOrThrow("impressions"))
          clicks = c.getLong(c.getColumnIndexOrThrow("clicks"))
        }
      }
      val ctr = if (impressions > 0) clicks.toDouble() / impressions.toDouble() else 0.0
      val cpc = if (clicks > 0) spend / clicks.toDouble() else 0.0

      DayDetails(
        date = date,
        bags = bags,
        ads = AdsMetrics(spend = spend, impressions = impressions, clicks = clicks, ctr = ctr, cpc = cpc)
      )
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

  suspend fun fmtMoney(v: Double): String = withContext(Dispatchers.Default) {
    val r = (v * 100.0).roundToInt() / 100.0
    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }
}
