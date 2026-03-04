package com.ml.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.AdsMetrics
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.BagOrders
import com.ml.app.domain.ColorValue
import com.ml.app.domain.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

class SQLiteRepo(private val context: Context) {

  private fun openDbReadOnly(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun openDbReadWrite(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    ensureSchema(db)
    return db
  }

  private fun ensureSchema(db: SQLiteDatabase) {
    // indexes are safe; db is local
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_date ON svodka(date)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_bag_id ON svodka(bag_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_type_key ON media(entity_type, entity_key)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bags_id ON bags(bag_id)")
  }

  private fun isTotalColor(c: String?): Boolean {
    val s = c?.trim() ?: return false
    return s == "__TOTAL__" || s == "TOTAL"
  }

  private fun normalizeColor(raw: String?): String? {
    if (raw == null) return null
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (isTotalColor(s)) return null
    val low = s.lowercase()

    if (low.contains("#")) return null
    if (low.contains("div/")) return null
    if (low.matches(Regex("-?\\d+(\\.\\d+)?"))) return null
    if (s.length > 25) return null
    if (low == "цвет") return null

    return s
  }

  private fun Cursor.getDoubleOrZero(name: String): Double {
    val i = getColumnIndex(name)
    if (i < 0 || isNull(i)) return 0.0
    return getDouble(i)
  }

  private fun Cursor.getLongFromRealOrZero(name: String): Long {
    val i = getColumnIndex(name)
    if (i < 0 || isNull(i)) return 0L
    // columns are REAL in your svodka; convert safely
    return getDouble(i).roundToLong()
  }

  private data class TotalRow(
    val price: Double?,
    val hypothesis: String?,
    val orders: Double,
    val stock: Double,
    val cpo: Double,
    val rk: AdsMetrics,
    val ig: AdsMetrics
  )

  private fun queryTotalRow(db: SQLiteDatabase, date: String, bagId: String): TotalRow {
    val sql = """
      SELECT
        price, hypothesis,
        COALESCE(orders,0) AS orders,
        COALESCE(stock,0)  AS stock,
        COALESCE(cpo,0)    AS cpo,

        COALESCE(rk_spend,0) AS rk_spend,
        COALESCE(rk_impressions,0) AS rk_impressions,
        COALESCE(rk_clicks,0) AS rk_clicks,

        COALESCE(ig_spend,0) AS ig_spend,
        COALESCE(ig_impressions,0) AS ig_impressions,
        COALESCE(ig_clicks,0) AS ig_clicks

      FROM svodka
      WHERE date=? AND bag_id=? AND color="__TOTAL__"
      LIMIT 1
    """.trimIndent()

    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      if (!c.moveToFirst()) {
        return TotalRow(null, null, 0.0, 0.0, 0.0, AdsMetrics(), AdsMetrics())
      }

      val ip = c.getColumnIndex("price")
      val ih = c.getColumnIndex("hypothesis")
      val price = if (ip >= 0 && !c.isNull(ip)) c.getDouble(ip) else null
      val hyp = if (ih >= 0 && !c.isNull(ih)) c.getString(ih) else null

      val rkSpend = c.getDoubleOrZero("rk_spend")
      val rkImpr = c.getLongFromRealOrZero("rk_impressions")
      val rkClicks = c.getLongFromRealOrZero("rk_clicks")
      val rkCtr = if (rkImpr > 0) (rkClicks.toDouble() / rkImpr.toDouble()) * 100.0 else 0.0
      val rkCpc = if (rkClicks > 0) rkSpend / rkClicks.toDouble() else 0.0
      val rk = AdsMetrics(spend = rkSpend, impressions = rkImpr, clicks = rkClicks, ctr = rkCtr, cpc = rkCpc)

      val igSpend = c.getDoubleOrZero("ig_spend")
      val igImpr = c.getLongFromRealOrZero("ig_impressions")
      val igClicks = c.getLongFromRealOrZero("ig_clicks")
      val igCtr = if (igImpr > 0) (igClicks.toDouble() / igImpr.toDouble()) * 100.0 else 0.0
      val igCpc = if (igClicks > 0) igSpend / igClicks.toDouble() else 0.0
      val ig = AdsMetrics(spend = igSpend, impressions = igImpr, clicks = igClicks, ctr = igCtr, cpc = igCpc)

      return TotalRow(
        price = price,
        hypothesis = hyp,
        orders = c.getDoubleOrZero("orders"),
        stock = c.getDoubleOrZero("stock"),
        cpo = c.getDoubleOrZero("cpo"),
        rk = rk,
        ig = ig
      )
    }
  }

  private fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT color, SUM(COALESCE(orders,0)) AS v
      FROM svodka
      WHERE date=? AND bag_id=? AND color <> "__TOTAL__"
      GROUP BY color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) {
        val color = normalizeColor(c.getString(ic)) ?: continue
        val v = c.getDouble(iv)
        if (v <= 0.0) continue
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  private fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT color, MAX(COALESCE(stock,0)) AS v
      FROM svodka
      WHERE date=? AND bag_id=? AND color <> "__TOTAL__"
      GROUP BY color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) {
        val color = normalizeColor(c.getString(ic)) ?: continue
        val v = c.getDouble(iv)
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  private fun queryBagNameMap(db: SQLiteDatabase): Map<String, String> {
    val out = HashMap<String, String>()
    db.rawQuery("SELECT bag_id, bag_name FROM bags", null).use { c ->
      val iId = c.getColumnIndexOrThrow("bag_id")
      val iName = c.getColumnIndexOrThrow("bag_name")
      while (c.moveToNext()) {
        val id = c.getString(iId)
        val name = c.getString(iName)
        if (!id.isNullOrBlank() && !name.isNullOrBlank()) out[id] = name
      }
    }
    return out
  }

  private fun queryImages(db: SQLiteDatabase): Map<String, String> {
    // key = bag_id
    val out = HashMap<String, String>()
    db.rawQuery(
      """
        SELECT entity_key AS bag_id,
               COALESCE(NULLIF(thumbnail_path,), image_path) AS p
        FROM media
        WHERE entity_type=bag
      """.trimIndent(),
      null
    ).use { c ->
      val iId = c.getColumnIndexOrThrow("bag_id")
      val iP = c.getColumnIndexOrThrow("p")
      while (c.moveToNext()) {
        val bagId = c.getString(iId)?.trim()
        var p = c.getString(iP)?.trim()
        if (bagId.isNullOrBlank() || p.isNullOrBlank()) continue
        p = p.removePrefix("/").replace("\\", "/")
        if (p.startsWith("dedup/")) p = "images/$p"
        out[bagId] = p
      }
    }
    return out
  }

  // --- Public API ---

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    try { openDbReadWrite().use { } } catch (_: Throwable) { /* ignore */ }

    openDbReadOnly().use { db ->
      val bagNames = queryBagNameMap(db)
      val images = queryImages(db)

      val dates = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT date FROM svodka ORDER BY date DESC LIMIT ?",
        arrayOf(limitDays.toString())
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) {
          val d = c.getString(i)
          if (!d.isNullOrBlank()) dates.add(d)
        }
      }

      val out = ArrayList<DaySummary>()
      for (date in dates) {
        val byBags = ArrayList<BagOrders>()

        db.rawQuery(
          """
            SELECT bag_id, SUM(COALESCE(orders,0)) AS tot
            FROM svodka
            WHERE date=? AND color="__TOTAL__"
            GROUP BY bag_id
            ORDER BY tot DESC
          """.trimIndent(),
          arrayOf(date)
        ).use { c ->
          val iId = c.getColumnIndexOrThrow("bag_id")
          val iTot = c.getColumnIndexOrThrow("tot")
          while (c.moveToNext()) {
            val bagId = c.getString(iId)
            val tot = c.getDouble(iTot)
            val bagName = bagNames[bagId] ?: bagId
            byBags.add(
              BagOrders(
                bag = bagName,
                orders = tot.toInt(),
                imagePath = images[bagId]
              )
            )
          }
        }

        val totalOrders = byBags.sumOf { it.orders }
        out.add(DaySummary(date = date, totalOrders = totalOrders, byBags = byBags))
      }
      out
    }
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    try { openDbReadWrite().use { } } catch (_: Throwable) { /* ignore */ }

    openDbReadOnly().use { db ->
      val bagNames = queryBagNameMap(db)
      val images = queryImages(db)

      val bagIds = ArrayList<String>()
      db.rawQuery(
        "SELECT DISTINCT bag_id FROM svodka WHERE date=? ORDER BY bag_id",
        arrayOf(date)
      ).use { c ->
        val i = c.getColumnIndexOrThrow("bag_id")
        while (c.moveToNext()) {
          val id = c.getString(i)
          if (!id.isNullOrBlank()) bagIds.add(id)
        }
      }

      val result = ArrayList<BagDayRow>()
      for (bagId in bagIds) {
        val total = queryTotalRow(db, date, bagId)

        val ordersByColors = queryOrdersByColors(db, date, bagId)
        val stockByColors = queryStockByColors(db, date, bagId)

        val totalOrders = total.orders
        val rk = total.rk
        val ig = total.ig

        val totalSpend = rk.spend + ig.spend
        val cpo = if (total.cpo > 0.0) total.cpo else if (totalOrders > 0) totalSpend / totalOrders else 0.0

        val totalImpr = rk.impressions + ig.impressions
        val totalClicks = rk.clicks + ig.clicks
        val totalCtr = if (totalImpr > 0) (totalClicks.toDouble() / totalImpr.toDouble()) * 100.0 else 0.0
        val totalCpc = if (totalClicks > 0) totalSpend / totalClicks.toDouble() else 0.0

        val totalAds = AdsMetrics(
          spend = totalSpend,
          impressions = totalImpr,
          clicks = totalClicks,
          ctr = totalCtr,
          cpc = totalCpc
        )

        val bagName = bagNames[bagId] ?: bagId
        result.add(
          BagDayRow(
            bag = bagName,
            price = total.price,
            hypothesis = total.hypothesis,
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
}
