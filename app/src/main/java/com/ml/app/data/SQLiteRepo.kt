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
    val dbFile: File = PackDbSync.dbFileToUse(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun packFile(rel: String?): String? {
    if (rel.isNullOrBlank()) return null
    val f = File(PackPaths.packDir(context), rel)
    return if (f.exists()) f.absolutePath else null
  }

  // svodka has per-color rows + TOTAL rows. For per-bag aggregates use only TOTAL rows.
  private fun totalColorWhere(): String = "AND s.color IN ('__TOTAL__','TOTAL')"

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)
      val days = ArrayList<DaySummary>()

      db.rawQuery(
        """
          SELECT s.date AS date,
                 s.bag_id AS bag_id,
                 COALESCE(b.bag_name, s.bag_id) AS bag_name,
                 SUM(COALESCE(s.orders,0)) AS orders,
                 SUM(COALESCE(s.rk_spend,0) + COALESCE(s.ig_spend,0)) AS spend,
                 MAX(s.price) AS price,
                 MAX(COALESCE(s.cogs,0)) AS cogs
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.date IS NOT NULL AND s.date != ''
            AND s.bag_id IS NOT NULL AND s.bag_id != ''
            ${totalColorWhere()}
          GROUP BY s.date, s.bag_id
          ORDER BY s.date DESC, orders DESC
        """.trimIndent(),
        null
      ).use { c ->
        val iDate = c.getColumnIndexOrThrow("date")
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iOrders = c.getColumnIndexOrThrow("orders")
        val iSpend = c.getColumnIndexOrThrow("spend")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iCogs = c.getColumnIndexOrThrow("cogs")

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
          val orders = c.getDouble(iOrders).roundToInt()
          val spend = c.getDouble(iSpend)
          val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          val cogs = c.getDouble(iCogs)

          curTotal += orders

          curList.add(
            BagOrdersSummary(
              bagId = bagId,
              bagName = bagName,
              orders = orders,
              imagePath = images[bagId],
              spend = spend,
              price = price,
              cogs = cogs
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
      val out = ArrayList<BagDayRow>()

      db.rawQuery(
        """
          SELECT s.bag_id AS bag_id,
                 COALESCE(b.bag_name, s.bag_id) AS bag_name,
                 MAX(s.price) AS price,
                 MAX(s.hypothesis) AS hypothesis,
                 SUM(COALESCE(s.orders,0)) AS orders,

                 MAX(COALESCE(s.cogs,0)) AS cogs,

                 SUM(COALESCE(s.rk_spend,0)) AS rk_spend,
                 SUM(COALESCE(s.rk_impressions,0)) AS rk_impressions,
                 SUM(COALESCE(s.rk_clicks,0)) AS rk_clicks,

                 SUM(COALESCE(s.ig_spend,0)) AS ig_spend,
                 SUM(COALESCE(s.ig_impressions,0)) AS ig_impressions,
                 SUM(COALESCE(s.ig_clicks,0)) AS ig_clicks
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.date=? AND s.bag_id IS NOT NULL AND s.bag_id != ''
            ${totalColorWhere()}
          GROUP BY s.bag_id
          ORDER BY orders DESC
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iHyp = c.getColumnIndexOrThrow("hypothesis")
        val iOrders = c.getColumnIndexOrThrow("orders")
        val iCogs = c.getColumnIndexOrThrow("cogs")

        val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
        val iRkImpr = c.getColumnIndexOrThrow("rk_impressions")
        val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")

        val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
        val iIgImpr = c.getColumnIndexOrThrow("ig_impressions")
        val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iId)
          val bagName = c.getString(iName)

          val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          val hyp = if (c.isNull(iHyp)) null else c.getString(iHyp)

          val totalOrders = c.getDouble(iOrders)
          val cogs = c.getDouble(iCogs)

          val rkSpend = c.getDouble(iRkSpend)
          val rkImpr = c.getLong(iRkImpr)
          val rkClicks = c.getLong(iRkClicks)

          val igSpend = c.getDouble(iIgSpend)
          val igImpr = c.getLong(iIgImpr)
          val igClicks = c.getLong(iIgClicks)

          val rkCtr = if (rkImpr > 0) rkClicks.toDouble() / rkImpr.toDouble() else 0.0
          val rkCpc = if (rkClicks > 0) rkSpend / rkClicks.toDouble() else 0.0

          val igCtr = if (igImpr > 0) igClicks.toDouble() / igImpr.toDouble() else 0.0
          val igCpc = if (igClicks > 0) igSpend / igClicks.toDouble() else 0.0

          val rk = AdsMetrics(spend = rkSpend, impressions = rkImpr, clicks = rkClicks, ctr = rkCtr, cpc = rkCpc)
          val ig = AdsMetrics(spend = igSpend, impressions = igImpr, clicks = igClicks, ctr = igCtr, cpc = igCpc)

          val totalSpend = rkSpend + igSpend
          val totalImpr = rkImpr + igImpr
          val totalClicks = rkClicks + igClicks
          val totalCtr = if (totalImpr > 0) totalClicks.toDouble() / totalImpr.toDouble() else 0.0
          val totalCpc = if (totalClicks > 0) totalSpend / totalClicks.toDouble() else 0.0
          val totalAds = AdsMetrics(spend = totalSpend, impressions = totalImpr, clicks = totalClicks, ctr = totalCtr, cpc = totalCpc)

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
              cogs = cogs,
              ordersByColors = queryOrdersByColors(db, date, bagId),
              stockByColors = queryStockByColors(db, date, bagId),
              rk = rk,
              ig = ig,
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

  suspend fun fmtMoney(v: Double): String = withContext(Dispatchers.Default) {
    val r = (v * 100.0).roundToInt() / 100.0
    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }

  // --------- USER DATA (merged DB) ----------
  private fun openDbReadWrite(): SQLiteDatabase {
    val dbFile: File = PackDbSync.dbFileToUse(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
  }

  data class BagUserRow(
    val bagId: String,
    val name: String?,
    val hypothesis: String?,
    val price: Double?,
    val cogs: Double?,
    val cardType: String?,
    val photoPath: String?
  )

  suspend fun getBagUser(bagId: String): BagUserRow? = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.rawQuery(
        "SELECT bag_id,name,hypothesis,price,cogs,card_type,photo_path FROM bag_user WHERE bag_id=?",
        arrayOf(bagId)
      ).use { c ->
        if (!c.moveToFirst()) return@withContext null
        fun str(col: String) = c.getString(c.getColumnIndexOrThrow(col))
        fun nstr(col: String) = if (c.isNull(c.getColumnIndexOrThrow(col))) null else str(col)
        fun ndbl(col: String) = if (c.isNull(c.getColumnIndexOrThrow(col))) null else c.getDouble(c.getColumnIndexOrThrow(col))

        BagUserRow(
          bagId = str("bag_id"),
          name = nstr("name"),
          hypothesis = nstr("hypothesis"),
          price = ndbl("price"),
          cogs = ndbl("cogs"),
          cardType = nstr("card_type"),
          photoPath = nstr("photo_path")
        )
      }
    }
  }

  suspend fun getBagUserColors(bagId: String): List<String> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val out = ArrayList<String>()
      db.rawQuery(
        "SELECT color FROM bag_user_colors WHERE bag_id=? ORDER BY color",
        arrayOf(bagId)
      ).use { c ->
        val i = c.getColumnIndexOrThrow("color")
        while (c.moveToNext()) out.add(c.getString(i))
      }
      out
    }
  }

  suspend fun upsertBagUser(
    bagId: String,
    name: String?,
    hypothesis: String?,
    price: Double?,
    cogs: Double?,
    cardType: String?,
    photoPath: String?
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        db.execSQL(
          "INSERT INTO bag_user(bag_id,name,hypothesis,price,cogs,card_type,photo_path) VALUES(?,?,?,?,?,?,?) " +
            "ON CONFLICT(bag_id) DO UPDATE SET " +
            "name=excluded.name, hypothesis=excluded.hypothesis, price=excluded.price, cogs=excluded.cogs, " +
            "card_type=excluded.card_type, photo_path=excluded.photo_path",
          arrayOf(bagId, name, hypothesis, price, cogs, cardType, photoPath)
        )
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

  suspend fun replaceBagUserColors(bagId: String, colors: List<String>) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        db.execSQL("DELETE FROM bag_user_colors WHERE bag_id=?", arrayOf(bagId))
        for (c in colors.distinct().filter { it.isNotBlank() }) {
          db.execSQL("INSERT OR IGNORE INTO bag_user_colors(bag_id,color) VALUES(?,?)", arrayOf(bagId, c))
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }



  data class BagPickerRow(
    val bagId: String,
    val bagName: String,
    val photoPath: String?
  )

  suspend fun listBagPickerRows(): List<BagPickerRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val out = ArrayList<BagPickerRow>()
      db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          u.photo_path AS photo_path
        FROM (
          SELECT DISTINCT bag_id
          FROM svodka
          WHERE bag_id IS NOT NULL AND bag_id != ''
        ) s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        ORDER BY bag_name COLLATE NOCASE
        """.trimIndent(),
        null
      ).use { c ->
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iPhoto = c.getColumnIndexOrThrow("photo_path")

        while (c.moveToNext()) {
          out.add(
            BagPickerRow(
              bagId = c.getString(iBagId),
              bagName = c.getString(iBagName),
              photoPath = if (c.isNull(iPhoto)) null else c.getString(iPhoto)
            )
          )
        }
      }
      out
    }
  }

  suspend fun deleteBagUsers(bagIds: List<String>) = withContext(Dispatchers.IO) {
    if (bagIds.isEmpty()) return@withContext

    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        for (id in bagIds.distinct()) {
          db.execSQL("DELETE FROM bag_user_colors WHERE bag_id=?", arrayOf(id))
          db.execSQL("DELETE FROM bag_user WHERE bag_id=?", arrayOf(id))
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }



  data class BagEditorSeed(
    val bagId: String,
    val bagName: String,
    val hypothesis: String?,
    val price: Double?,
    val cogs: Double?,
    val colors: List<String>
  )

  suspend fun getBagEditorSeed(bagId: String): BagEditorSeed? = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val latestDate = db.rawQuery(
        """
        SELECT MAX(date) AS d
        FROM svodka
        WHERE bag_id=? AND date IS NOT NULL AND date!=''
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0)
      } ?: return@withContext null

      val head = db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          COALESCE(NULLIF(u.hypothesis,''), MAX(s.hypothesis)) AS hypothesis,
          COALESCE(u.price, MAX(s.price)) AS price,
          COALESCE(u.cogs, MAX(s.cogs)) AS cogs
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        WHERE s.bag_id=? AND s.date=? AND s.color IN ('__TOTAL__','TOTAL')
        GROUP BY s.bag_id, COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id), COALESCE(NULLIF(u.hypothesis,''), MAX(s.hypothesis)), COALESCE(u.price, MAX(s.price)), COALESCE(u.cogs, MAX(s.cogs))
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        if (!c.moveToFirst()) return@withContext null
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iHyp = c.getColumnIndexOrThrow("hypothesis")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iCogs = c.getColumnIndexOrThrow("cogs")
        arrayOf(
          c.getString(iBagId),
          c.getString(iBagName),
          if (c.isNull(iHyp)) null else c.getString(iHyp),
          if (c.isNull(iPrice)) null else c.getDouble(iPrice),
          if (c.isNull(iCogs)) null else c.getDouble(iCogs)
        )
      }

      val colors = ArrayList<String>()
      db.rawQuery(
        """
        SELECT DISTINCT color
        FROM svodka
        WHERE bag_id=? AND date=? AND color IS NOT NULL AND color!='' AND color NOT IN ('__TOTAL__','TOTAL')
        ORDER BY color COLLATE NOCASE
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        while (c.moveToNext()) colors.add(c.getString(0))
      }

      BagEditorSeed(
        bagId = head[0] as String,
        bagName = head[1] as String,
        hypothesis = head[2] as String?,
        price = head[3] as Double?,
        cogs = head[4] as Double?,
        colors = colors
      )
    }
  }

}
