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

    private fun openDbReadWrite(): SQLiteDatabase {
        val dbFile: File = PackDbSync.dbFileToUse(context)
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }

    private fun packFile(rel: String?): String? {
        if (rel.isNullOrBlank()) return null
        val f = File(PackPaths.packDir(context), rel)
        return if (f.exists()) f.absolutePath else null
    }

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
                    val orders = c.getDouble(iOrders).roundToInt()
                    val spend = c.getDouble(iSpend)
                    val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
                    val cogs = c.getDouble(iCogs)

                    curTotal += orders
                    curList.add(BagOrdersSummary(bagId, c.getString(iBagName), orders, images[bagId], spend, price, cogs))
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
                    val rkSpend = c.getDouble(iRkSpend)
                    val rkImpr = c.getLong(iRkImpr)
                    val rkClicks = c.getLong(iRkClicks)
                    val igSpend = c.getDouble(iIgSpend)
                    val igImpr = c.getLong(iIgImpr)
                    val igClicks = c.getLong(iIgClicks)

                    val rk = AdsMetrics(rkSpend, rkImpr, rkClicks, 
                        if (rkImpr > 0) rkClicks.toDouble()/rkImpr else 0.0, 
                        if (rkClicks > 0) rkSpend/rkClicks else 0.0)
                    val ig = AdsMetrics(igSpend, igImpr, igClicks, 
                        if (igImpr > 0) igClicks.toDouble()/igImpr else 0.0, 
                        if (igClicks > 0) igSpend/igClicks else 0.0)

                    val totalSpend = rkSpend + igSpend
                    val totalImpr = rkImpr + igImpr
                    val totalClicks = rkClicks + igClicks
                    val totalAds = AdsMetrics(totalSpend, totalImpr, totalClicks,
                        if (totalImpr > 0) (totalClicks.toDouble()/totalImpr) else 0.0,
                        if (totalClicks > 0) totalSpend/totalClicks else 0.0)

                    out.add(BagDayRow(
                        bagId = bagId,
                        bagName = c.getString(iName),
                        price = if (c.isNull(iPrice)) null else c.getDouble(iPrice),
                        hypothesis = if (c.isNull(iHyp)) null else c.getString(iHyp),
                        imagePath = images[bagId],
                        totalOrders = c.getDouble(iOrders),
                        totalSpend = totalSpend,
                        cpo = if (c.getDouble(iOrders) > 0) totalSpend / c.getDouble(iOrders) else 0.0,
                        cogs = c.getDouble(iCogs),
                        ordersByColors = queryOrdersByColors(db, date, bagId),
                        stockByColors = queryStockByColors(db, date, bagId),
                        rk = rk, ig = ig, totalAds = totalAds
                    ))
                }
            }
            out
        }
    }

    fun queryImagesByBagId(db: SQLiteDatabase): Map<String, String?> {
        val out = HashMap<String, String?>()
        db.rawQuery("SELECT entity_key, COALESCE(thumbnail_path, image_path) AS p FROM media WHERE entity_type='bag'", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = packFile(c.getString(1))
            }
        }
        return out
    }

    fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
        val out = ArrayList<ColorValue>()
        db.rawQuery(
            "SELECT color, SUM(COALESCE(orders,0)) AS v FROM svodka WHERE date=? AND bag_id=? AND color NOT IN ('__TOTAL__','TOTAL') GROUP BY color ORDER BY v DESC",
            arrayOf(date, bagId)
        ).use { c ->
            while (c.moveToNext()) out.add(ColorValue(c.getString(0), c.getDouble(1)))
        }
        return out
    }

    fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
        val out = ArrayList<ColorValue>()
        db.rawQuery(
            "SELECT color, SUM(COALESCE(stock,0)) AS v FROM svodka WHERE date=? AND bag_id=? AND color NOT IN ('__TOTAL__','TOTAL') GROUP BY color ORDER BY v DESC",
            arrayOf(date, bagId)
        ).use { c ->
            while (c.moveToNext()) out.add(ColorValue(c.getString(0), c.getDouble(1)))
        }
        return out
    }

    suspend fun listAllBags(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        openDbReadWrite().use { db ->
            val out = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT bag_id, bag_name FROM bags ORDER BY bag_name", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
            }
            out
        }
    }
}
