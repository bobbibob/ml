package com.ml.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.AdsMetrics
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.ColorValue
import com.ml.app.domain.DaySummary
import com.ml.app.domain.BagOrders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_svodka_date_bag_color ON svodka(date, bag, color)")

    val cols = HashSet<String>()
    db.rawQuery("PRAGMA table_info(svodka)", null).use { c ->
      val idx = c.getColumnIndex("name")
      while (c.moveToNext()) cols.add(c.getString(idx))
    }

    fun addCol(name: String, type: String, def: String? = null) {
      if (!cols.contains(name)) {
        val d = if (def != null) " DEFAULT $def" else ""
        db.execSQL("ALTER TABLE svodka ADD COLUMN $name $type$d")
      }
    }

    // ads (RK/IG)
    addCol("rk_spend", "REAL", "0")
    addCol("rk_impressions", "INTEGER", "0")
    addCol("rk_clicks", "INTEGER", "0")
    addCol("rk_ctr", "REAL", "0")
    addCol("rk_cpc", "REAL", "0")

    addCol("ig_spend", "REAL", "0")
    addCol("ig_impressions", "INTEGER", "0")
    addCol("ig_clicks", "INTEGER", "0")
    addCol("ig_ctr", "REAL", "0")
    addCol("ig_cpc", "REAL", "0")

    // optional KPI/econ – чтобы приложение не падало, если БД “тонкая”
    addCol("cpo", "REAL", "0")
    addCol("ctr", "REAL", "0")
    addCol("cpc", "REAL", "0")
    addCol("profit_net", "REAL", "0")
    addCol("roi_pct", "REAL", "0")
    addCol("cogs", "REAL", "0")
    addCol("notes", "TEXT", "")
    addCol("updated_at", "TEXT", "(datetime(now))")
  }

  private fun normKey(s: String): String {
    return s.trim()
      .lowercase()
      .replace("ё", "е")
      .replace(Regex("\\s+"), "")
      .replace(Regex("[^a-z0-9а-я]"), "")
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

    // excel junk
    if (low.contains("#")) return null
    if (low.contains("div/")) return null

    // numeric / percent-like / weird tokens
    if (low.matches(Regex("-?\\d+(\\.\\d+)?"))) return null

    // too long -> usually trash
    if (s.length > 25) return null

    // ignore obvious headers
    if (low.contains("гипотез")) return null
    if (low.contains("заказ")) return null
    if (low.contains("остат")) return null
    if (low == "цвет") return null

    return s
  }

  // --- Public API ---

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    try { openDbReadWrite().use { } } catch (_: Throwable) { /* ignore */ }

    openDbReadOnly().use { db ->
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
        val images = queryImages(db)
        val byBags = queryBagTotalsForDate(db, date, images)
        val totalOrders = byBags.sumOf { it.orders }
        out.add(
          DaySummary(
            date = date,
            totalOrders = totalOrders,
            byBags = byBags.sortedByDescending { it.orders }
          )
        )
      }
      out
    }
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    try { openDbReadWrite().use { } } catch (_: Throwable) { /* ignore */ }

    openDbReadOnly().use { db ->
      val images = queryImages(db) // bag -> relative path

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
        val total = queryTotalRow(db, date, bag)

        val price = total.price
        val hypothesis = total.hypothesis

        val ordersByColors = queryOrdersByColors(db, date, bag)
        val stockByColors = queryStockByColors(db, date, bag)

        val totalOrders =
          if (total.orders > 0.0) total.orders
          else ordersByColors.sumOf { it.value }

        val rk = total.rk
        val ig = total.ig

        val totalSpend = rk.spend + ig.spend
        val cpo =
          if (total.cpo > 0.0) total.cpo
          else if (totalOrders > 0.0) totalSpend / totalOrders else 0.0

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

        result.add(
          BagDayRow(
            bag = bag,
            price = price,
            hypothesis = hypothesis,
            imagePath = images[bag] ?: images[normKey(bag)],
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

  // --- Queries ---

  private data class TotalRow(
    val price: Double?,
    val hypothesis: String?,
    val orders: Double,
    val stock: Double,
    val cpo: Double,
    val rk: AdsMetrics,
    val ig: AdsMetrics
  )

  private fun Cursor.getDoubleOrZero(col: String): Double {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return 0.0
    return getDouble(i)
  }

  private fun Cursor.getLongOrZero(col: String): Long {
    val i = getColumnIndex(col)
    if (i < 0 || isNull(i)) return 0L
    return getLong(i)
  }

  private fun queryTotalRow(db: SQLiteDatabase, date: String, bag: String): TotalRow {
    // IMPORTANT: prefer __TOTAL__ over TOTAL if both exist
    val sql = """
      SELECT
        price, hypothesis,
        COALESCE(orders,0) AS orders,
        COALESCE(stock,0) AS stock,
        COALESCE(cpo,0) AS cpo,
        COALESCE(rk_spend,0) AS rk_spend,
        COALESCE(rk_impressions,0) AS rk_impressions,
        COALESCE(rk_clicks,0) AS rk_clicks,
        COALESCE(ig_spend,0) AS ig_spend,
        COALESCE(ig_impressions,0) AS ig_impressions,
        COALESCE(ig_clicks,0) AS ig_clicks
      FROM svodka
      WHERE date=? AND bag=? AND (color="__TOTAL__" OR color="TOTAL")
      ORDER BY CASE WHEN color="__TOTAL__" THEN 0 ELSE 1 END
      LIMIT 1
    """.trimIndent()

    db.rawQuery(sql, arrayOf(date, bag)).use { c ->
      if (!c.moveToFirst()) {
        return TotalRow(
          price = null,
          hypothesis = null,
          orders = 0.0,
          stock = 0.0,
          cpo = 0.0,
          rk = AdsMetrics(),
          ig = AdsMetrics()
        )
      }

      val ip = c.getColumnIndex("price")
      val ih = c.getColumnIndex("hypothesis")
      val price = if (ip >= 0 && !c.isNull(ip)) c.getDouble(ip) else null
      val hyp = if (ih >= 0 && !c.isNull(ih)) c.getString(ih) else null

      val rkSpend = c.getDoubleOrZero("rk_spend")
      val rkImpr = c.getLongOrZero("rk_impressions")
      val rkClicks = c.getLongOrZero("rk_clicks")
      val rkCtr = if (rkImpr > 0) (rkClicks.toDouble() / rkImpr.toDouble()) * 100.0 else 0.0
      val rkCpc = if (rkClicks > 0) rkSpend / rkClicks.toDouble() else 0.0
      val rk = AdsMetrics(spend = rkSpend, impressions = rkImpr, clicks = rkClicks, ctr = rkCtr, cpc = rkCpc)

      val igSpend = c.getDoubleOrZero("ig_spend")
      val igImpr = c.getLongOrZero("ig_impressions")
      val igClicks = c.getLongOrZero("ig_clicks")
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

  private fun queryOrdersByColors(db: SQLiteDatabase, date: String, bag: String): List<ColorValue> {
    val sql = """
      SELECT color, SUM(COALESCE(orders,0)) AS v
      FROM svodka
      WHERE date=? AND bag=? AND color <> "__TOTAL__" AND color <> "TOTAL"
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
      WHERE date=? AND bag=? AND color <> "__TOTAL__" AND color <> "TOTAL"
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

  private fun queryBagTotalsForDate(db: SQLiteDatabase, date: String, images: Map<String, String>): List<BagOrders> {
    val bags = ArrayList<String>()
    db.rawQuery("SELECT DISTINCT bag FROM svodka WHERE date=?", arrayOf(date)).use { c ->
      val i = c.getColumnIndexOrThrow("bag")
      while (c.moveToNext()) {
        val b = c.getString(i)
        if (!b.isNullOrBlank()) bags.add(b)
      }
    }

    val out = ArrayList<BagOrders>()
    for (bag in bags) {
      val tot = queryTotalRow(db, date, bag).orders
      val orders =
        if (tot > 0.0) tot
        else queryOrdersByColors(db, date, bag).sumOf { it.value }

      out.add(
        BagOrders(
          bag = bag,
          orders = orders.toInt(),
          imagePath = images[bag] ?: images[normKey(bag)]
        )
      )
    }
    return out
  }

  private fun queryImages(db: SQLiteDatabase): Map<String, String> {
    val out = HashMap<String, String>()
    // media might not exist in early packs
    try {
      db.rawQuery(
        """
          SELECT
            entity_key,
            COALESCE(NULLIF(thumbnail_path,), NULLIF(image_path,)) AS p
          FROM media
          WHERE entity_key IS NOT NULL
        """.trimIndent(),
        null
      ).use { c ->
        val ik = c.getColumnIndexOrThrow("entity_key")
        val ip = c.getColumnIndexOrThrow("p")
        while (c.moveToNext()) {
          val k = c.getString(ik)?.trim()
          val p0 = c.getString(ip)?.trim()
          if (k.isNullOrBlank() || p0.isNullOrBlank()) continue

          // normalize path (we store relative paths inside pack)
          val p = p0.removePrefix("/")

          out[k] = p
          out[normKey(k)] = p
        }
      }
    } catch (_: Throwable) {
      // ignore
    }
    return out
  }
}
