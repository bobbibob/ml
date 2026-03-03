package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.ColorOrders
import com.ml.app.domain.SourceOrders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SQLiteRepo(private val context: Context) {

  private fun openDbReadOnly(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val totals = queryTotals(db, date)
      if (totals.isEmpty()) return@withContext emptyList()
      val byColors = queryColors(db, date)
      val bySources = querySources(db, date)
      val images = queryImages(db) // entity_key == bag (если иначе — скажешь, поправим)

      totals.map { (bag, base) ->
        BagDayRow(
          bag = bag,
          price = base.price,
          stock = base.stock,
          hypothesis = base.hypothesis,
          totalOrders = base.totalOrders,
          byColors = byColors[bag].orEmpty(),
          bySources = bySources[bag].orEmpty(),
          imagePath = images[bag]
        )
      }.sortedByDescending { it.totalOrders }
    }
  }

  private data class Base(val price: Double?, val stock: Double?, val hypothesis: String?, val totalOrders: Double)

  private fun queryTotals(db: SQLiteDatabase, date: String): Map<String, Base> {
    val sql = """
      SELECT bag,
             SUM(COALESCE(orders,0)) AS total_orders,
             MAX(price) AS price,
             MAX(stock) AS stock,
             MAX(hypothesis) AS hypothesis
      FROM svodka
      WHERE date = ?
      GROUP BY bag
    """.trimIndent()

    val map = LinkedHashMap<String, Base>()
    db.rawQuery(sql, arrayOf(date)).use { c ->
      val iBag = c.getColumnIndexOrThrow("bag")
      val iTotal = c.getColumnIndexOrThrow("total_orders")
      val iPrice = c.getColumnIndexOrThrow("price")
      val iStock = c.getColumnIndexOrThrow("stock")
      val iHyp = c.getColumnIndexOrThrow("hypothesis")
      while (c.moveToNext()) {
        val bag = c.getString(iBag)
        val total = c.getDouble(iTotal)
        val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
        val stock = if (c.isNull(iStock)) null else c.getDouble(iStock)
        val hyp = if (c.isNull(iHyp)) null else c.getString(iHyp)
        map[bag] = Base(price, stock, hyp, total)
      }
    }
    return map
  }

  private fun queryColors(db: SQLiteDatabase, date: String): Map<String, List<ColorOrders>> {
    val sql = """
      SELECT bag, color, SUM(COALESCE(orders,0)) AS color_orders
      FROM svodka
      WHERE date = ?
      GROUP BY bag, color
      ORDER BY bag, color_orders DESC
    """.trimIndent()

    val map = LinkedHashMap<String, MutableList<ColorOrders>>()
    db.rawQuery(sql, arrayOf(date)).use { c ->
      val iBag = c.getColumnIndexOrThrow("bag")
      val iColor = c.getColumnIndexOrThrow("color")
      val iCnt = c.getColumnIndexOrThrow("color_orders")
      while (c.moveToNext()) {
        val bag = c.getString(iBag)
        val color = c.getString(iColor)
        val cnt = c.getDouble(iCnt)
        map.getOrPut(bag) { mutableListOf() }.add(ColorOrders(color, cnt))
      }
    }
    return map
  }

  private fun querySources(db: SQLiteDatabase, date: String): Map<String, List<SourceOrders>> {
    val sql = """
      SELECT bag, source, SUM(COALESCE(orders,0)) AS source_orders
      FROM svodka
      WHERE date = ?
      GROUP BY bag, source
      ORDER BY bag, source_orders DESC
    """.trimIndent()

    val map = LinkedHashMap<String, MutableList<SourceOrders>>()
    db.rawQuery(sql, arrayOf(date)).use { c ->
      val iBag = c.getColumnIndexOrThrow("bag")
      val iSource = c.getColumnIndexOrThrow("source")
      val iCnt = c.getColumnIndexOrThrow("source_orders")
      while (c.moveToNext()) {
        val bag = c.getString(iBag)
        val source = c.getString(iSource)
        val cnt = c.getDouble(iCnt)
        map.getOrPut(bag) { mutableListOf() }.add(SourceOrders(source, cnt))
      }
    }
    return map
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
