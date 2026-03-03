package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.AdsMetrics
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.ColorValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SQLiteRepo(private val context: Context) {

  private fun openDbReadOnly(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    // read-only db тоже может выполнять CREATE INDEX IF NOT EXISTS? нет. Поэтому ensureSchema делаем в write-режиме ниже.
    return db
  }

  private fun openDbReadWrite(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    ensureSchema(db)
    return db
  }

  private fun ensureSchema(db: SQLiteDatabase) {
    // unique index for __TOTAL__ rows and safe UPSERTs
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_svodka_date_bag_color ON svodka(date, bag, color)")

    // add ads columns if missing
    val cols = HashSet<String>()
    db.rawQuery("PRAGMA table_info(svodka)", null).use { c ->
      val idx = c.getColumnIndex("name")
      while (c.moveToNext()) cols.add(c.getString(idx))
    }
    fun addCol(name: String, type: String) {
      if (!cols.contains(name)) db.execSQL("ALTER TABLE svodka ADD COLUMN $name $type")
    }

    addCol("rk_spend", "REAL")
    addCol("rk_impressions", "INTEGER")
    addCol("rk_clicks", "INTEGER")
    addCol("rk_ctr", "REAL")
    addCol("rk_cpc", "REAL")

    addCol("ig_spend", "REAL")
    addCol("ig_impressions", "INTEGER")
    addCol("ig_clicks", "INTEGER")
    addCol("ig_ctr", "REAL")
    addCol("ig_cpc", "REAL")
  }

  private fun normalizeColor(raw: String?): String? {
    if (raw == null) return null
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s == "__TOTAL__") return null
    val low = s.lowercase()

    // obvious junk / excel errors
    if (low.contains("#")) return null
    if (low.contains("div/")) return null

    // numeric -> not a color
    if (low.matches(Regex("-?\\d+(\\.\\d+)?"))) return null

    if (s.length > 25) return null
    return s
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    // ensure schema once (write mode), then read
    try { openDbReadWrite().use { /* ensureSchema ran */ } } catch (_: Throwable) { /* ignore */ }

    openDbReadOnly().use { db ->
      val images = queryImages(db) // bag -> image_path

      // list bags for this date (exclude __TOTAL__ rows; still allow bags that only have __TOTAL__ by OR)
      val bags = LinkedHashSet<String>()
      db.rawQuery(
        "SELECT DISTINCT bag FROM svodka WHERE date=?",
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
        val base = queryBase(db, date, bag)
        val ordersByColors = queryOrdersByColors(db, date, bag)
        val stockByColors = queryStockByColors(db, date, bag)
        val ads = queryAds(db, date, bag)

        val totalOrders = ordersByColors.sumOf { it.value }
        val totalSpend = ads.first.spend + ads.second.spend
        val cpo = if (totalOrders > 0.0) totalSpend / totalOrders else 0.0

        val totalImpr = ads.first.impressions + ads.second.impressions
        val totalClicks = ads.first.clicks + ads.second.clicks
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
            bag = bag,
            price = base.first,
            hypothesis = base.second,
            imagePath = images[bag],
            totalOrders = totalOrders,
            totalSpend = totalSpend,
            cpo = cpo,
            ordersByColors = ordersByColors,
            stockByColors = stockByColors,
            rk = ads.first,
            ig = ads.second,
            totalAds = totalAds
          )
        )
      }

      result.sortedByDescending { it.totalOrders }
    }
  }

  // price + hypothesis (max for day)
  private fun queryBase(db: SQLiteDatabase, date: String, bag: String): Pair<Double?, String?> {
    val sql = """
      SELECT MAX(price) AS price, MAX(hypothesis) AS hypothesis
      FROM svodka
      WHERE date=? AND bag=?
    """.trimIndent()
    db.rawQuery(sql, arrayOf(date, bag)).use { c ->
      return if (c.moveToFirst()) {
        val ip = c.getColumnIndexOrThrow("price")
        val ih = c.getColumnIndexOrThrow("hypothesis")
        val price = if (c.isNull(ip)) null else c.getDouble(ip)
        val hyp = if (c.isNull(ih)) null else c.getString(ih)
        price to hyp
      } else null to null
    }
  }

  private fun queryOrdersByColors(db: SQLiteDatabase, date: String, bag: String): List<ColorValue> {
    val sql = """
      SELECT color, SUM(COALESCE(orders,0)) AS v
      FROM svodka
      WHERE date=? AND bag=? AND color <> "__TOTAL__"
      GROUP BY color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bag)).use { c ->
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

  private fun queryStockByColors(db: SQLiteDatabase, date: String, bag: String): List<ColorValue> {
    val sql = """
      SELECT color, MAX(COALESCE(stock,0)) AS v
      FROM svodka
      WHERE date=? AND bag=? AND color <> "__TOTAL__"
      GROUP BY color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bag)).use { c ->
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

  // returns Pair(rk, ig) from __TOTAL__ row; if absent -> zeros
  private fun queryAds(db: SQLiteDatabase, date: String, bag: String): Pair<AdsMetrics, AdsMetrics> {
    val sql = """
      SELECT
        COALESCE(rk_spend,0) AS rk_spend,
        COALESCE(rk_impressions,0) AS rk_impr,
        COALESCE(rk_clicks,0) AS rk_clicks,
        COALESCE(rk_ctr,0) AS rk_ctr,
        COALESCE(rk_cpc,0) AS rk_cpc,
        COALESCE(ig_spend,0) AS ig_spend,
        COALESCE(ig_impressions,0) AS ig_impr,
        COALESCE(ig_clicks,0) AS ig_clicks,
        COALESCE(ig_ctr,0) AS ig_ctr,
        COALESCE(ig_cpc,0) AS ig_cpc
      FROM svodka
      WHERE date=? AND bag=? AND color="__TOTAL__"
      LIMIT 1
    """.trimIndent()

    db.rawQuery(sql, arrayOf(date, bag)).use { c ->
      if (!c.moveToFirst()) return AdsMetrics() to AdsMetrics()
      fun gD(name: String) = c.getDouble(c.getColumnIndexOrThrow(name))
      fun gL(name: String) = c.getLong(c.getColumnIndexOrThrow(name))

      val rk = AdsMetrics(
        spend = gD("rk_spend"),
        impressions = gL("rk_impr"),
        clicks = gL("rk_clicks"),
        ctr = gD("rk_ctr"),
        cpc = gD("rk_cpc")
      )
      val ig = AdsMetrics(
        spend = gD("ig_spend"),
        impressions = gL("ig_impr"),
        clicks = gL("ig_clicks"),
        ctr = gD("ig_ctr"),
        cpc = gD("ig_cpc")
      )
      return rk to ig
    }
  }

  private fun queryImages(db: SQLiteDatabase): Map<String, String> {
    val sql = """
      SELECT entity_key, image_path
      FROM media
      WHERE image_path IS NOT NULL
    """.trimIndent()

    val map = LinkedHashMap<String, String>()
    db.rawQuery(sql, null).use { c ->
      val iKey = c.getColumnIndexOrThrow("entity_key")
      val iPath = c.getColumnIndexOrThrow("image_path")
      while (c.moveToNext()) {
        val key = c.getString(iKey)
        val path = c.getString(iPath)
        if (!map.containsKey(key)) map[key] = path
      }
    }
    return map
  }
}
