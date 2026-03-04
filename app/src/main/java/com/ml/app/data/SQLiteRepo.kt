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

  suspend fun queryDates(): List<String> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val out = ArrayList<String>()
      db.rawQuery(
        """
          SELECT DISTINCT date
          FROM svodka
          WHERE date IS NOT NULL AND date != ''
          ORDER BY date DESC
        """.trimIndent(),
        null
      ).use { c ->
        val i = c.getColumnIndexOrThrow("date")
        while (c.moveToNext()) out.add(c.getString(i))
      }
      out
    }
  }

  suspend fun queryBagsForDate(date: String): List<BagShort> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val sql = """
        SELECT bag_id, MAX(bag_name) AS bag_name
        FROM svodka
        WHERE date=? AND bag_id IS NOT NULL AND bag_id != ''
        GROUP BY bag_id
        ORDER BY bag_name COLLATE NOCASE
      """.trimIndent()

      val out = ArrayList<BagShort>()
      db.rawQuery(sql, arrayOf(date)).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        while (c.moveToNext()) {
          out.add(BagShort(c.getString(iId), c.getString(iName)))
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

  private fun queryBase(db: SQLiteDatabase, date: String, bagId: String): Pair<Double?, String?> {
    val sql = """
      SELECT MAX(price) AS price, MAX(hypothesis) AS hypothesis
      FROM svodka
      WHERE date=? AND bag_id=?
    """.trimIndent()

    var price: Double? = null
    var hyp: String? = null
    db.rawQuery(sql, arrayOf(date, bagId)).use { c ->
      if (c.moveToFirst()) {
        if (!c.isNull(c.getColumnIndexOrThrow("price"))) price = c.getDouble(c.getColumnIndexOrThrow("price"))
        if (!c.isNull(c.getColumnIndexOrThrow("hypothesis"))) hyp = c.getString(c.getColumnIndexOrThrow("hypothesis"))
      }
    }
    return price to hyp
  }

  fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT s.color, SUM(COALESCE(s.orders,0)) AS v
      FROM svodka s
      LEFT JOIN colors c ON c.color = s.color
      WHERE s.date=? AND s.bag_id=? AND s.color NOT IN ("__TOTAL__","TOTAL")
      GROUP BY s.color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c2 ->
      val ic = c2.getColumnIndexOrThrow("color")
      val iv = c2.getColumnIndexOrThrow("v")
      while (c2.moveToNext()) {
        val color = c2.getString(ic)
        val v = c2.getDouble(iv)
        if (v <= 0.0) continue
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val sql = """
      SELECT s.color, SUM(COALESCE(s.stock,0)) AS v
      FROM svodka s
      LEFT JOIN colors c ON c.color = s.color
      WHERE s.date=? AND s.bag_id=? AND s.color NOT IN ("__TOTAL__","TOTAL")
      GROUP BY s.color
      ORDER BY v DESC
    """.trimIndent()

    val out = ArrayList<ColorValue>()
    db.rawQuery(sql, arrayOf(date, bagId)).use { c2 ->
      val ic = c2.getColumnIndexOrThrow("color")
      val iv = c2.getColumnIndexOrThrow("v")
      while (c2.moveToNext()) {
        val color = c2.getString(ic)
        val v = c2.getDouble(iv)
        if (v <= 0.0) continue
        out.add(ColorValue(color, v))
      }
    }
    return out
  }

  suspend fun querySummary(date: String, bagId: String): BagSummary = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)
      val (price, hypothesis) = queryBase(db, date, bagId)
      val orders = queryOrdersByColors(db, date, bagId)
      val stock = queryStockByColors(db, date, bagId)

      // total orders/stock for quick top cards
      val totalOrders = orders.sumOf { it.v }
      val totalStock = stock.sumOf { it.v }

      // pick a representative image for the bag (by bagId key)
      val image = images[bagId]

      BagSummary(
        date = date,
        bagId = bagId,
        price = price,
        hypothesis = hypothesis,
        imagePath = image,
        totalOrders = totalOrders,
        totalStock = totalStock,
        ordersByColor = orders,
        stockByColor = stock
      )
    }
  }

  // Simple helper for UI numbers: pretty rounding
  fun fmt(v: Double?): String {
    if (v == null) return "—"
    val r = (v * 100.0).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }
}
