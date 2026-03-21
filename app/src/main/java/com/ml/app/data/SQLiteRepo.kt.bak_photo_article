package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Instant
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import kotlin.math.roundToInt

class SQLiteRepo(private val context: Context) {

  private fun ensureDailySummarySyncQueueTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS daily_summary_sync_queue(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        summary_date TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        last_error TEXT,
        updated_at TEXT NOT NULL
      );
      """.trimIndent()
    )
  }


  private fun openDbReadOnly(): SQLiteDatabase {
    val merged = PackDbSync.mergedDbFile(context)
    if (!merged.exists() || merged.length() == 0L) {
      runCatching { PackDbSync.refreshMergedDb(context) }
    }
    val dbFile: File = PackDbSync.dbFileToUse(context)
    require(dbFile.exists() && dbFile.length() > 0L) {
      "Readable DB missing: ${dbFile.absolutePath}"
    }
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
    openDbReadWrite().use { db ->

      db.rawQuery("PRAGMA table_info(svodka)", null).use { c ->
        val cols = mutableSetOf<String>()
        while (c.moveToNext()) cols.add(c.getString(1))

        if (!cols.contains("delivery_fee")) {
          kotlin.runCatching { db.execSQL("ALTER TABLE svodka ADD COLUMN delivery_fee REAL") }
        }

        if (!cols.contains("hypothesis")) {
          kotlin.runCatching { db.execSQL("ALTER TABLE svodka ADD COLUMN hypothesis TEXT") }
        }
      }

      val images = queryImagesByBagId(db)
      val days = ArrayList<DaySummary>()
        val bagNames = queryBagNamesById(db)

      db.rawQuery(
        """
          SELECT s.date AS date,
                 s.bag_id AS bag_id,
                 s.bag_id AS bag_name,
                 SUM(COALESCE(s.orders,0)) AS orders,
                 SUM(COALESCE(s.rk_spend,0) + COALESCE(s.ig_spend,0)) AS spend,
                 MAX(s.price) AS price,
                 MAX(COALESCE(s.cogs,0)) AS cogs
          FROM svodka s
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
        val iDeliveryFee = c.getColumnIndex("delivery_fee")

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
            val bagName = bagNames[bagId] ?: c.getString(iBagName)
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
    openDbReadWrite().use { db ->
      val images = queryImagesByBagId(db)
        val bagNames = queryBagNamesById(db)
      val out = ArrayList<BagDayRow>()

      db.rawQuery(
        """
          SELECT s.bag_id AS bag_id,
                 s.bag_id AS bag_name,
                 MAX(s.price) AS price,
                 MAX(s.hypothesis) AS hypothesis,
                 SUM(COALESCE(s.orders,0)) AS orders,

                 MAX(COALESCE(s.cogs,0)) AS cogs,
                 MAX(s.delivery_fee) AS delivery_fee,

                 SUM(COALESCE(s.rk_spend,0)) AS rk_spend,
                 SUM(COALESCE(s.rk_impressions,0)) AS rk_impressions,
                 SUM(COALESCE(s.rk_clicks,0)) AS rk_clicks,

                 SUM(COALESCE(s.ig_spend,0)) AS ig_spend,
                 SUM(COALESCE(s.ig_impressions,0)) AS ig_impressions,
                 SUM(COALESCE(s.ig_clicks,0)) AS ig_clicks
          FROM svodka s
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
        val iDeliveryFee = c.getColumnIndexOrThrow("delivery_fee")

        val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
        val iRkImpr = c.getColumnIndexOrThrow("rk_impressions")
        val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")

        val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
        val iIgImpr = c.getColumnIndexOrThrow("ig_impressions")
        val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iId)
            val bagName = bagNames[bagId] ?: c.getString(iName)

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
              deliveryFee = if (c.isNull(iDeliveryFee)) null else c.getDouble(iDeliveryFee),
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

    fun queryBagNamesById(db: SQLiteDatabase): Map<String, String> {
      val out = HashMap<String, String>()
      try {
        db.rawQuery(
          """
          SELECT bag_id, bag_name
          FROM bags
          WHERE bag_id IS NOT NULL AND bag_id != ''
            AND bag_name IS NOT NULL AND bag_name != ''
          """.trimIndent(),
          null
        ).use { c ->
          val iId = c.getColumnIndexOrThrow("bag_id")
          val iName = c.getColumnIndexOrThrow("bag_name")
          while (c.moveToNext()) {
            out[c.getString(iId)] = c.getString(iName)
          }
        }
      } catch (_: Throwable) {
      }
      return out
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
    val merged = PackDbSync.mergedDbFile(context)
    if (!merged.exists() || merged.length() == 0L) {
      runCatching { PackDbSync.refreshMergedDb(context) }
    }
    val dbFile: File = PackDbSync.dbFileToUse(context)
    val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    kotlin.runCatching { db.execSQL("ALTER TABLE bag_user ADD COLUMN delivery_fee REAL") }
    return db
  }


  private fun ensureMlArticleColumns(db: SQLiteDatabase) {
    val cols = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(bag_user)", null).use { c ->
      while (c.moveToNext()) cols.add(c.getString(1))
    }

    fun add(name: String, type: String) {
      if (!cols.contains(name)) kotlin.runCatching { db.execSQL("ALTER TABLE bag_user ADD COLUMN $name $type") }
    }

    add("ml_listing_id", "TEXT")
    add("ml_listing_code", "TEXT")
    add("ml_status", "TEXT")
    add("ml_price", "REAL")
    add("ml_promo_price", "REAL")
    add("ml_currency", "TEXT")
    add("ml_stock_total", "INTEGER")
    add("ml_visits", "INTEGER")
    add("ml_sold_total", "INTEGER")
    add("ml_sale_fee_type", "TEXT")
    add("ml_sale_fee_percent", "REAL")
    add("ml_sale_fee_amount", "REAL")
    add("ml_shipping_mode", "TEXT")
    add("ml_shipping_cost", "REAL")
    add("ml_shipping_paid_by", "TEXT")
    add("ml_net_amount", "REAL")
    add("ml_quality_score", "INTEGER")
    add("ml_quality_level", "TEXT")
    add("ml_experience_score", "INTEGER")
    add("ml_experience_level", "TEXT")
    add("ml_bulk_price_enabled", "INTEGER")
    add("ml_bulk_price_min_qty", "INTEGER")
    add("ml_bulk_price_amount", "REAL")
    add("ml_variant_id", "TEXT")
    add("ml_color", "TEXT")
    add("ml_size", "TEXT")
    add("ml_material", "TEXT")
    add("ml_attr_json", "TEXT")
    add("ml_image_main_url", "TEXT")
    add("ml_listing_raw_json", "TEXT")
    add("ml_variant_raw_json", "TEXT")
    add("ml_synced_at", "INTEGER")
  }


  private fun mlArticleFromSku(sku: String?): String? {
    val v = sku?.trim().orEmpty()
    if (v.isBlank()) return null
    val i = v.lastIndexOf('-')
    return if (i > 0) v.substring(0, i) else v
  }

  private fun jstr(o: JSONObject, key: String): String? =
    if (!o.has(key) || o.isNull(key)) null else o.optString(key, null)

  private fun jint(o: JSONObject, key: String): Int? =
    if (!o.has(key) || o.isNull(key)) null else when (val v = o.opt(key)) {
      is Number -> v.toInt()
      is String -> v.toIntOrNull()
      else -> null
    }

  private fun jdbl(o: JSONObject, key: String): Double? =
    if (!o.has(key) || o.isNull(key)) null else when (val v = o.opt(key)) {
      is Number -> v.toDouble()
      is String -> v.replace(",", ".").toDoubleOrNull()
      else -> null
    }


  fun normalizeImportedMlArticleNames() {
    openDbReadWrite().use { db ->
      ensureMlArticleColumns(db)
      db.execSQL(
        """
        UPDATE bag_user
        SET name = bag_id
        WHERE ml_listing_id IS NOT NULL
          AND ml_listing_id != ''
          AND bag_id IS NOT NULL
          AND bag_id != ''
        """.trimIndent()
      )
    }
  }

  fun importMlListingsJsonToArticles(json: String): Int {
    val root = try {
      JSONObject(json)
    } catch (_: JSONException) {
      return 0
    }

    val items = root.optJSONArray("items") ?: return 0
    val sourceUrl = root.optString("url", "")
    val syncedAt = root.optLong("captured_at", System.currentTimeMillis())

    openDbReadWrite().use { db ->
      ensureMlArticleColumns(db)

      db.beginTransaction()
      try {
        var saved = 0

        for (i in 0 until items.length()) {
          val item = items.optJSONObject(i) ?: continue

          val listingId = jstr(item, "listing_id")
          val listingCode = jstr(item, "listing_code")
          val title = jstr(item, "title")
          val status = jstr(item, "status")
          val price = jdbl(item, "price")
          val promoPrice = jdbl(item, "promo_price")
          val currency = jstr(item, "currency")
          val stockTotal = jint(item, "stock_total")
          val visits = jint(item, "visits")
          val soldTotal = jint(item, "sold_total")
          val saleFeeType = jstr(item, "sale_fee_type")
          val saleFeePercent = jdbl(item, "sale_fee_percent")
          val saleFeeAmount = jdbl(item, "sale_fee_amount")
          val shippingMode = jstr(item, "shipping_mode")
          val shippingCost = jdbl(item, "shipping_cost")
          val shippingPaidBy = jstr(item, "shipping_paid_by")
          val netAmount = jdbl(item, "net_amount")
          val qualityScore = jint(item, "quality_score")
          val qualityLevel = jstr(item, "quality_level")
          val experienceScore = jint(item, "experience_score")
          val experienceLevel = jstr(item, "experience_level")
          val bulkPriceEnabled = if (item.optBoolean("bulk_price_enabled", false)) 1 else 0
          val bulkPriceMinQty = jint(item, "bulk_price_min_qty")
          val bulkPriceAmount = jdbl(item, "bulk_price_amount")
          val listingRaw = item.toString()

          val variants = item.optJSONArray("variants")
          if (variants != null && variants.length() > 0) {
            for (j in 0 until variants.length()) {
              val v = variants.optJSONObject(j) ?: continue
              val sku = jstr(v, "sku")
              val color = jstr(v, "color")
              val size = jstr(v, "size")
              val material = jstr(v, "material")
              val variantId = jstr(v, "variant_id")
              val imageMainUrl = jstr(v, "image_main_url")
              val attrJson = if (v.has("attributes") && !v.isNull("attributes")) {
                v.getJSONObject("attributes").toString()
              } else {
                "{}"
              }
              val variantRaw = v.toString()

              val articleCode = mlArticleFromSku(sku)
              val bagId = when {
                !articleCode.isNullOrBlank() -> articleCode
                !listingId.isNullOrBlank() -> listingId
                else -> continue
              }

              val displayName = bagId
              val effectivePrice = promoPrice ?: price

              db.execSQL(
                """
                INSERT INTO bag_user(
                  bag_id,name,hypothesis,price,cogs,card_type,photo_path,
                  ml_listing_id,ml_listing_code,ml_status,ml_price,ml_promo_price,ml_currency,
                  ml_stock_total,ml_visits,ml_sold_total,
                  ml_sale_fee_type,ml_sale_fee_percent,ml_sale_fee_amount,
                  ml_shipping_mode,ml_shipping_cost,ml_shipping_paid_by,
                  ml_net_amount,ml_quality_score,ml_quality_level,
                  ml_experience_score,ml_experience_level,
                  ml_bulk_price_enabled,ml_bulk_price_min_qty,ml_bulk_price_amount,
                  ml_variant_id,ml_color,ml_size,ml_material,ml_attr_json,ml_image_main_url,
                  ml_listing_raw_json,ml_variant_raw_json,ml_synced_at
                ) VALUES(?,?,?,?,?,?,?,
                         ?,?,?,?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?,?,
                         ?,?,?,
                         ?,?,?,?,?,?,
                         ?,?,?)
                ON CONFLICT(bag_id) DO UPDATE SET
                  name=excluded.name,
                  price=excluded.price,
                  ml_listing_id=excluded.ml_listing_id,
                  ml_listing_code=excluded.ml_listing_code,
                  ml_status=excluded.ml_status,
                  ml_price=excluded.ml_price,
                  ml_promo_price=excluded.ml_promo_price,
                  ml_currency=excluded.ml_currency,
                  ml_stock_total=excluded.ml_stock_total,
                  ml_visits=excluded.ml_visits,
                  ml_sold_total=excluded.ml_sold_total,
                  ml_sale_fee_type=excluded.ml_sale_fee_type,
                  ml_sale_fee_percent=excluded.ml_sale_fee_percent,
                  ml_sale_fee_amount=excluded.ml_sale_fee_amount,
                  ml_shipping_mode=excluded.ml_shipping_mode,
                  ml_shipping_cost=excluded.ml_shipping_cost,
                  ml_shipping_paid_by=excluded.ml_shipping_paid_by,
                  ml_net_amount=excluded.ml_net_amount,
                  ml_quality_score=excluded.ml_quality_score,
                  ml_quality_level=excluded.ml_quality_level,
                  ml_experience_score=excluded.ml_experience_score,
                  ml_experience_level=excluded.ml_experience_level,
                  ml_bulk_price_enabled=excluded.ml_bulk_price_enabled,
                  ml_bulk_price_min_qty=excluded.ml_bulk_price_min_qty,
                  ml_bulk_price_amount=excluded.ml_bulk_price_amount,
                  ml_variant_id=excluded.ml_variant_id,
                  ml_color=excluded.ml_color,
                  ml_size=excluded.ml_size,
                  ml_material=excluded.ml_material,
                  ml_attr_json=excluded.ml_attr_json,
                  ml_image_main_url=excluded.ml_image_main_url,
                  ml_listing_raw_json=excluded.ml_listing_raw_json,
                  ml_variant_raw_json=excluded.ml_variant_raw_json,
                  ml_synced_at=excluded.ml_synced_at
                """.trimIndent(),
                arrayOf(
                  bagId, displayName, null, effectivePrice, null, null, null,
                  listingId, listingCode, status, price, promoPrice, currency,
                  stockTotal, visits, soldTotal,
                  saleFeeType, saleFeePercent, saleFeeAmount,
                  shippingMode, shippingCost, shippingPaidBy,
                  netAmount, qualityScore, qualityLevel,
                  experienceScore, experienceLevel,
                  bulkPriceEnabled, bulkPriceMinQty, bulkPriceAmount,
                  variantId, color, size, material, attrJson, imageMainUrl,
                  listingRaw, variantRaw, syncedAt
                )
              )

              if (!color.isNullOrBlank()) {
                db.execSQL("INSERT OR IGNORE INTO bag_user_colors(bag_id,color) VALUES(?,?)", arrayOf(bagId, color))
              }
              saved++
            }
          } else {
            val bagId = listingId ?: continue
            val displayName = bagId
            val effectivePrice = promoPrice ?: price

            db.execSQL(
              """
              INSERT INTO bag_user(
                bag_id,name,hypothesis,price,cogs,card_type,photo_path,
                ml_listing_id,ml_listing_code,ml_status,ml_price,ml_promo_price,ml_currency,
                ml_stock_total,ml_visits,ml_sold_total,
                ml_sale_fee_type,ml_sale_fee_percent,ml_sale_fee_amount,
                ml_shipping_mode,ml_shipping_cost,ml_shipping_paid_by,
                ml_net_amount,ml_quality_score,ml_quality_level,
                ml_experience_score,ml_experience_level,
                ml_bulk_price_enabled,ml_bulk_price_min_qty,ml_bulk_price_amount,
                ml_variant_id,ml_color,ml_size,ml_material,ml_attr_json,ml_image_main_url,
                ml_listing_raw_json,ml_variant_raw_json,ml_synced_at
              ) VALUES(?,?,?,?,?,?,?,
                       ?,?,?,?,?,?,
                       ?,?,?,
                       ?,?,?,
                       ?,?,?,
                       ?,?,?,
                       ?,?,
                       ?,?,?,
                       ?,?,?,?,?,?,
                       ?,?,?)
              ON CONFLICT(bag_id) DO UPDATE SET
                name=excluded.name,
                price=excluded.price,
                ml_listing_id=excluded.ml_listing_id,
                ml_listing_code=excluded.ml_listing_code,
                ml_status=excluded.ml_status,
                ml_price=excluded.ml_price,
                ml_promo_price=excluded.ml_promo_price,
                ml_currency=excluded.ml_currency,
                ml_stock_total=excluded.ml_stock_total,
                ml_visits=excluded.ml_visits,
                ml_sold_total=excluded.ml_sold_total,
                ml_sale_fee_type=excluded.ml_sale_fee_type,
                ml_sale_fee_percent=excluded.ml_sale_fee_percent,
                ml_sale_fee_amount=excluded.ml_sale_fee_amount,
                ml_shipping_mode=excluded.ml_shipping_mode,
                ml_shipping_cost=excluded.ml_shipping_cost,
                ml_shipping_paid_by=excluded.ml_shipping_paid_by,
                ml_net_amount=excluded.ml_net_amount,
                ml_quality_score=excluded.ml_quality_score,
                ml_quality_level=excluded.ml_quality_level,
                ml_experience_score=excluded.ml_experience_score,
                ml_experience_level=excluded.ml_experience_level,
                ml_bulk_price_enabled=excluded.ml_bulk_price_enabled,
                ml_bulk_price_min_qty=excluded.ml_bulk_price_min_qty,
                ml_bulk_price_amount=excluded.ml_bulk_price_amount,
                ml_listing_raw_json=excluded.ml_listing_raw_json,
                ml_synced_at=excluded.ml_synced_at
              """.trimIndent(),
              arrayOf(
                bagId, displayName, null, effectivePrice, null, null, null,
                listingId, listingCode, status, price, promoPrice, currency,
                stockTotal, visits, soldTotal,
                saleFeeType, saleFeePercent, saleFeeAmount,
                shippingMode, shippingCost, shippingPaidBy,
                netAmount, qualityScore, qualityLevel,
                experienceScore, experienceLevel,
                bulkPriceEnabled, bulkPriceMinQty, bulkPriceAmount,
                null, null, null, null, "{}", null,
                listingRaw, null, syncedAt
              )
            )
            saved++
          }
        }

        db.setTransactionSuccessful()
        return saved
      } finally {
        db.endTransaction()
      }
    }
  }

  data class BagUserRow(
    val bagId: String,
    val name: String?,
    val hypothesis: String?,
    val price: Double?,
    val cogs: Double?,
    val deliveryFee: Double?,
    val cardType: String?,
    val photoPath: String?
  )

  suspend fun getBagUser(bagId: String): BagUserRow? = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      kotlin.runCatching { db.execSQL("ALTER TABLE bag_user ADD COLUMN delivery_fee REAL") }
      db.rawQuery(
        "SELECT bag_id,name,hypothesis,price,cogs,delivery_fee,card_type,photo_path FROM bag_user WHERE bag_id=?",
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
          deliveryFee = ndbl("delivery_fee"),
          cardType = nstr("card_type"),
          photoPath = nstr("photo_path")
        )
      }
    }
  }


  data class BagColorPriceRow(
    val color: String,
    val price: Double?
  )

  suspend fun getBagColorPrices(bagId: String): List<BagColorPriceRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS bag_user_color_price(
          bag_id TEXT NOT NULL,
          color TEXT NOT NULL,
          price REAL,
          PRIMARY KEY(bag_id, color)
        )
        """.trimIndent()
      )
      val out = ArrayList<BagColorPriceRow>()
      db.rawQuery(
        "SELECT color, price FROM bag_user_color_price WHERE bag_id=? ORDER BY color",
        arrayOf(bagId)
      ).use { c ->
        val iColor = c.getColumnIndexOrThrow("color")
        val iPrice = c.getColumnIndexOrThrow("price")
        while (c.moveToNext()) {
          out.add(
            BagColorPriceRow(
              color = c.getString(iColor),
              price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
            )
          )
        }
      }
      out
    }
  }

  suspend fun replaceBagColorPrices(
    bagId: String,
    rows: List<BagColorPriceRow>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS bag_user_color_price(
          bag_id TEXT NOT NULL,
          color TEXT NOT NULL,
          price REAL,
          PRIMARY KEY(bag_id, color)
        )
        """.trimIndent()
      )
      db.beginTransaction()
      try {
        db.execSQL("DELETE FROM bag_user_color_price WHERE bag_id=?", arrayOf(bagId))
        for (row in rows.distinctBy { it.color }.filter { it.color.isNotBlank() }) {
          db.execSQL(
            "INSERT OR REPLACE INTO bag_user_color_price(bag_id,color,price) VALUES(?,?,?)",
            arrayOf(bagId, row.color, row.price)
          )
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
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
          x.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), x.bag_name, x.bag_id) AS bag_name,
          u.photo_path AS photo_path
        FROM (
          SELECT
            s.bag_id AS bag_id,
            s.bag_id AS bag_name
          FROM (
            SELECT DISTINCT bag_id
            FROM svodka
            WHERE bag_id IS NOT NULL AND bag_id != ''
          ) s

          UNION

          SELECT
            u2.bag_id AS bag_id,
            COALESCE(NULLIF(u2.name,''), u2.bag_id) AS bag_name
          FROM bag_user u2
          WHERE u2.bag_id IS NOT NULL AND u2.bag_id != ''
        ) x
        LEFT JOIN bag_user u ON u.bag_id = x.bag_id
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
        SELECT MAX(date)
        FROM svodka
        WHERE bag_id=? AND date IS NOT NULL AND date!=''
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0)
      } ?: return@withContext null

      var bagName: String? = null
      var hypothesis: String? = null
      var price: Double? = null
      var cogs: Double? = null

      db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_name,
          s.hypothesis AS hypothesis,
          s.price AS price,
          s.cogs AS cogs
        FROM svodka s
                WHERE s.bag_id=? AND s.date=? AND s.color IN ('__TOTAL__','TOTAL')
        LIMIT 1
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        if (c.moveToFirst()) {
          val iBagName = c.getColumnIndexOrThrow("bag_name")
          val iHyp = c.getColumnIndexOrThrow("hypothesis")
          val iPrice = c.getColumnIndexOrThrow("price")
          val iCogs = c.getColumnIndexOrThrow("cogs")
          bagName = c.getString(iBagName)
          hypothesis = if (c.isNull(iHyp)) null else c.getString(iHyp)
          price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          cogs = if (c.isNull(iCogs)) null else c.getDouble(iCogs)
        }
      }

      if (bagName == null) {
        db.rawQuery(
          """
          SELECT s.bag_id AS bag_name
          FROM svodka s
                    WHERE s.bag_id=?
          LIMIT 1
          """.trimIndent(),
          arrayOf(bagId)
        ).use { c ->
          if (c.moveToFirst()) bagName = c.getString(0)
        }
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

      val finalBagName = bagName ?: return@withContext null
      BagEditorSeed(
        bagId = bagId,
        bagName = finalBagName,
        hypothesis = hypothesis,
        price = price,
        cogs = cogs,
        colors = colors
      )
    }
  }

  data class BagStockOverrideRow(
    val effectiveDate: String,
    val bagId: String,
    val color: String,
    val stock: Double
  )

  data class BagStockResolvedRow(
    val bagId: String,
    val color: String,
    val stock: Double
  )

  suspend fun replaceBagStockOverrides(
    effectiveDate: String,
    bagId: String,
    rows: List<Pair<String, Double>>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS bag_stock_override(
          effective_date TEXT NOT NULL,
          bag_id TEXT NOT NULL,
          color TEXT NOT NULL,
          stock REAL NOT NULL,
          PRIMARY KEY(effective_date, bag_id, color)
        )
        """.trimIndent()
      )

      db.beginTransaction()
      try {
        db.execSQL(
          "DELETE FROM bag_stock_override WHERE effective_date=? AND bag_id=?",
          arrayOf(effectiveDate, bagId)
        )

        for ((color, stock) in rows.filter { it.first.isNotBlank() }) {
          db.execSQL(
            "INSERT OR REPLACE INTO bag_stock_override(effective_date, bag_id, color, stock) VALUES(?,?,?,?)",
            arrayOf(effectiveDate, bagId, color, stock)
          )
        }

        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

  suspend fun getEffectiveStockOverrides(date: String): List<BagStockResolvedRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS bag_stock_override(
          effective_date TEXT NOT NULL,
          bag_id TEXT NOT NULL,
          color TEXT NOT NULL,
          stock REAL NOT NULL,
          PRIMARY KEY(effective_date, bag_id, color)
        )
        """.trimIndent()
      )

      val out = ArrayList<BagStockResolvedRow>()
      db.rawQuery(
        """
        SELECT o1.bag_id, o1.color, o1.stock
        FROM bag_stock_override o1
        JOIN (
          SELECT bag_id, color, MAX(effective_date) AS max_date
          FROM bag_stock_override
          WHERE effective_date <= ?
          GROUP BY bag_id, color
        ) x
          ON x.bag_id = o1.bag_id
         AND x.color = o1.color
         AND x.max_date = o1.effective_date
        ORDER BY o1.bag_id, o1.color
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iStock = c.getColumnIndexOrThrow("stock")
        while (c.moveToNext()) {
          out.add(
            BagStockResolvedRow(
              bagId = c.getString(iBag),
              color = c.getString(iColor),
              stock = c.getDouble(iStock)
            )
          )
        }
      }
      out
    }
  }



  data class StockResolvedRow(
    val bagId: String,
    val color: String,
    val stock: Double
  )

  suspend fun getResolvedStocksForDate(date: String): List<StockResolvedRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_date_bag_color ON svodka(date, bag_id, color)")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS bag_stock_override(
          effective_date TEXT NOT NULL,
          bag_id TEXT NOT NULL,
          color TEXT NOT NULL,
          stock REAL NOT NULL,
          PRIMARY KEY(effective_date, bag_id, color)
        )
        """.trimIndent()
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_date_bag_color ON bag_stock_override(effective_date, bag_id, color)")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_bag_color_date ON bag_stock_override(bag_id, color, effective_date)")

      val base = LinkedHashMap<Pair<String, String>, Double>()

      db.rawQuery(
        """
        SELECT s1.bag_id, s1.color, s1.stock
        FROM svodka s1
        JOIN (
          SELECT bag_id, color, MAX(date) AS max_date
          FROM svodka
          WHERE date <= ?
            AND bag_id IS NOT NULL AND bag_id != ''
            AND color IS NOT NULL AND color != ''
            AND color NOT IN ('__TOTAL__','TOTAL')
            AND stock IS NOT NULL
          GROUP BY bag_id, color
        ) x
          ON x.bag_id = s1.bag_id
         AND x.color = s1.color
         AND x.max_date = s1.date
        ORDER BY s1.bag_id, s1.color
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iStock = c.getColumnIndexOrThrow("stock")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          val stock = if (c.isNull(iStock)) 0.0 else c.getDouble(iStock)
          base[bagId to color] = stock
        }
      }

      db.rawQuery(
        """
        SELECT o1.bag_id, o1.color, o1.stock
        FROM bag_stock_override o1
        JOIN (
          SELECT bag_id, color, MAX(effective_date) AS max_date
          FROM bag_stock_override
          WHERE effective_date <= ?
          GROUP BY bag_id, color
        ) x
          ON x.bag_id = o1.bag_id
         AND x.color = o1.color
         AND x.max_date = o1.effective_date
        ORDER BY o1.bag_id, o1.color
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iStock = c.getColumnIndexOrThrow("stock")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          val stock = if (c.isNull(iStock)) 0.0 else c.getDouble(iStock)
          base[bagId to color] = stock
        }
      }

      val out = ArrayList<StockResolvedRow>()
      for ((key, value) in base.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
        out.add(
          StockResolvedRow(
            bagId = key.first,
            color = key.second,
            stock = value
          )
        )
      }
      out
    }
  }



  data class StockBagMeta(
    val bagId: String,
    val bagName: String,
    val photoPath: String?
  )

  suspend fun listStockBagMeta(): List<StockBagMeta> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val out = ArrayList<StockBagMeta>()
      db.rawQuery(
        """
        SELECT
          x.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, x.bag_id) AS bag_name,
          COALESCE(
            NULLIF(u.photo_path,''),
            (
              SELECT m.image_path
              FROM media m
              WHERE m.entity_type='bag'
                AND m.entity_key=x.bag_id
                AND m.image_path IS NOT NULL
                AND m.image_path!=''
              LIMIT 1
            )
          ) AS photo_path
        FROM (
          SELECT DISTINCT bag_id
          FROM svodka
          WHERE bag_id IS NOT NULL AND bag_id!=''
        ) x
        LEFT JOIN bags b ON b.bag_id = x.bag_id
        LEFT JOIN bag_user u ON u.bag_id = x.bag_id
        ORDER BY bag_name COLLATE NOCASE
        """.trimIndent(),
        null
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        val iPhoto = c.getColumnIndexOrThrow("photo_path")
        while (c.moveToNext()) {
          out.add(
            StockBagMeta(
              bagId = c.getString(iBag),
              bagName = c.getString(iName),
              photoPath = if (c.isNull(iPhoto)) null else c.getString(iPhoto)
            )
          )
        }
      }
      out
    }
  }



  data class SummaryBagColorMeta(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<String>
  )

  suspend fun listSummaryBagColorMeta(): List<SummaryBagColorMeta> = withContext(Dispatchers.IO) {
    fun normalizeColorKey(value: String): String {
      return value
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
        .trimEnd('.')
    }

    val today = LocalDate.now().toString()
    val bagMeta = listStockBagMeta()

    val currentInStock = getResolvedStocksForDate(today)
      .filter { it.stock > 0.0 }
      .groupBy { it.bagId }

    bagMeta.mapNotNull { bag ->
      val rawColors = currentInStock[bag.bagId]
        ?.map { it.color.trim().replace(Regex("\\s+"), " ") }
        .orEmpty()

      val deduped = linkedMapOf<String, String>()
      for (color in rawColors) {
        val key = normalizeColorKey(color)
        if (key.isNotBlank() && key !in deduped) {
          deduped[key] = color
        }
      }

      val colors = deduped.values
        .sortedBy { it.lowercase() }

      if (colors.isEmpty()) null
      else SummaryBagColorMeta(
        bagId = bag.bagId,
        bagName = bag.bagName,
        photoPath = bag.photoPath,
        colors = colors
      )
    }
  }



  data class DailySummaryBagSave(
    val bagId: String,
    val ordersByColor: List<Pair<String, Int>>,
    val rkEnabled: Boolean,
    val rkSpend: Double?,
    val rkImpressions: Long?,
    val rkClicks: Long?,
    val rkStake: Double?,
    val igEnabled: Boolean,
    val igSpend: Double?,
    val igImpressions: Long?,
    val igClicks: Long?
  )

  data class DailySummaryDraft(
    val orders: Map<String, Int>,
    val rkEnabled: Map<String, Boolean>,
    val rkSpend: Map<String, String>,
    val rkImpressions: Map<String, String>,
    val rkClicks: Map<String, String>,
    val rkStake: Map<String, String>,
    val igEnabled: Map<String, Boolean>,
    val igSpend: Map<String, String>,
    val igImpressions: Map<String, String>,
    val igClicks: Map<String, String>
  )

  

  suspend fun deleteDailySummaryByDate(date: String) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        "DELETE FROM svodka WHERE date=?",
        arrayOf(date)
      )
    }
  }

  suspend fun getRemoteSyncedSummaryDates(): List<String> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val out = ArrayList<String>()
      db.rawQuery(
        """
        SELECT DISTINCT date
        FROM svodka
        WHERE source='remote-sync'
        ORDER BY date DESC
        """.trimIndent(),
        null
      ).use { c ->
        while (c.moveToNext()) out.add(c.getString(0))
      }
      out
    }
  }

  suspend fun deleteRemoteSyncedSummaryByDate(date: String) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.execSQL(
        "DELETE FROM svodka WHERE date=? AND source='remote-sync'",
        arrayOf(date)
      )
    }
  }



  suspend fun loadDailySummaryDraft(date: String): DailySummaryDraft = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val orders = linkedMapOf<String, Int>()
      val rkEnabled = linkedMapOf<String, Boolean>()
      val rkSpend = linkedMapOf<String, String>()
      val rkImpressions = linkedMapOf<String, String>()
      val rkClicks = linkedMapOf<String, String>()
      val rkStake = linkedMapOf<String, String>()
      val igEnabled = linkedMapOf<String, Boolean>()
      val igSpend = linkedMapOf<String, String>()
      val igImpressions = linkedMapOf<String, String>()
      val igClicks = linkedMapOf<String, String>()

      db.rawQuery(
        """
        SELECT bag_id, color, orders
        FROM svodka
        WHERE date=?
          AND bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
          AND color NOT IN ('__TOTAL__','TOTAL')
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iOrders = c.getColumnIndexOrThrow("orders")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          val value = if (c.isNull(iOrders)) 0 else c.getDouble(iOrders).toInt()
          orders["$bagId::$color"] = value
        }
      }

      db.rawQuery(
        """
        SELECT
          bag_id,
          rk_spend, rk_impressions, rk_clicks, stake_pct,
          ig_spend, ig_impressions, ig_clicks
        FROM svodka
        WHERE date=?
          AND color='__TOTAL__'
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
        val iRkImp = c.getColumnIndexOrThrow("rk_impressions")
        val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")
        val iStake = c.getColumnIndexOrThrow("stake_pct")
        val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
        val iIgImp = c.getColumnIndexOrThrow("ig_impressions")
        val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iBag)

          val rkSpendVal = if (c.isNull(iRkSpend)) 0.0 else c.getDouble(iRkSpend)
          val rkImpVal = if (c.isNull(iRkImp)) 0L else c.getDouble(iRkImp).toLong()
          val rkClicksVal = if (c.isNull(iRkClicks)) 0L else c.getDouble(iRkClicks).toLong()
          val stakeVal = if (c.isNull(iStake)) 0.0 else c.getDouble(iStake)

          val igSpendVal = if (c.isNull(iIgSpend)) 0.0 else c.getDouble(iIgSpend)
          val igImpVal = if (c.isNull(iIgImp)) 0L else c.getDouble(iIgImp).toLong()
          val igClicksVal = if (c.isNull(iIgClicks)) 0L else c.getDouble(iIgClicks).toLong()

          rkEnabled[bagId] = rkSpendVal != 0.0 || rkImpVal != 0L || rkClicksVal != 0L || stakeVal != 0.0
          rkSpend[bagId] = if (rkSpendVal == 0.0) "" else rkSpendVal.toString()
          rkImpressions[bagId] = if (rkImpVal == 0L) "" else rkImpVal.toString()
          rkClicks[bagId] = if (rkClicksVal == 0L) "" else rkClicksVal.toString()
          rkStake[bagId] = if (stakeVal == 0.0) "" else stakeVal.toString()

          igEnabled[bagId] = igSpendVal != 0.0 || igImpVal != 0L || igClicksVal != 0L
          igSpend[bagId] = if (igSpendVal == 0.0) "" else igSpendVal.toString()
          igImpressions[bagId] = if (igImpVal == 0L) "" else igImpVal.toString()
          igClicks[bagId] = if (igClicksVal == 0L) "" else igClicksVal.toString()
        }
      }

      DailySummaryDraft(
        orders = orders,
        rkEnabled = rkEnabled,
        rkSpend = rkSpend,
        rkImpressions = rkImpressions,
        rkClicks = rkClicks,
        rkStake = rkStake,
        igEnabled = igEnabled,
        igSpend = igSpend,
        igImpressions = igImpressions,
        igClicks = igClicks
      )
    }
  }


  data class PendingDailySummarySync(
    val id: Long,
    val summaryDate: String,
    val bags: List<DailySummaryBagSave>,
    val status: String,
    val lastError: String?
  )

  private fun serializeDailySummaryBags(bags: List<DailySummaryBagSave>): String {
    val arr = JSONArray()
    for (bag in bags) {
      val o = JSONObject()
      o.put("bagId", bag.bagId)
      val orders = JSONArray()
      for ((color, count) in bag.ordersByColor) {
        val row = JSONObject()
        row.put("color", color)
        row.put("count", count)
        orders.put(row)
      }
      o.put("ordersByColor", orders)
      o.put("rkEnabled", bag.rkEnabled)
      o.put("rkSpend", bag.rkSpend)
      o.put("rkImpressions", bag.rkImpressions)
      o.put("rkClicks", bag.rkClicks)
      o.put("rkStake", bag.rkStake)
      o.put("igEnabled", bag.igEnabled)
      o.put("igSpend", bag.igSpend)
      o.put("igImpressions", bag.igImpressions)
      o.put("igClicks", bag.igClicks)
      arr.put(o)
    }
    return arr.toString()
  }

  private fun deserializeDailySummaryBags(json: String): List<DailySummaryBagSave> {
    val arr = JSONArray(json)
    val out = ArrayList<DailySummaryBagSave>()
    for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      val orders = ArrayList<Pair<String, Int>>()
      val ordersArr = o.getJSONArray("ordersByColor")
      for (j in 0 until ordersArr.length()) {
        val row = ordersArr.getJSONObject(j)
        orders.add(row.getString("color") to row.getInt("count"))
      }
      out.add(
        DailySummaryBagSave(
          bagId = o.getString("bagId"),
          ordersByColor = orders,
          rkEnabled = o.optBoolean("rkEnabled", false),
          rkSpend = if (o.isNull("rkSpend")) null else o.optDouble("rkSpend"),
          rkImpressions = if (o.isNull("rkImpressions")) null else o.optLong("rkImpressions"),
          rkClicks = if (o.isNull("rkClicks")) null else o.optLong("rkClicks"),
          rkStake = if (o.isNull("rkStake")) null else o.optDouble("rkStake"),
          igEnabled = o.optBoolean("igEnabled", false),
          igSpend = if (o.isNull("igSpend")) null else o.optDouble("igSpend"),
          igImpressions = if (o.isNull("igImpressions")) null else o.optLong("igImpressions"),
          igClicks = if (o.isNull("igClicks")) null else o.optLong("igClicks")
        )
      )
    }
    return out
  }

  suspend fun enqueueDailySummarySync(
    date: String,
    bags: List<DailySummaryBagSave>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      ensureDailySummarySyncQueueTable(db)
      db.execSQL(
        "INSERT INTO daily_summary_sync_queue(summary_date,payload_json,status,last_error,updated_at) VALUES(?,?,?,?,?)",
        arrayOf(date, serializeDailySummaryBags(bags), "pending", null, Instant.now().toString())
      )
    }
  }

  suspend fun getPendingDailySummarySyncItems(): List<PendingDailySummarySync> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      ensureDailySummarySyncQueueTable(db)
      val out = ArrayList<PendingDailySummarySync>()
      db.rawQuery(
        """
        SELECT id, summary_date, payload_json, status, last_error
        FROM daily_summary_sync_queue
        WHERE status IN ('pending','error')
        ORDER BY id ASC
        """.trimIndent(),
        null
      ).use { c ->
        while (c.moveToNext()) {
          out.add(
            PendingDailySummarySync(
              id = c.getLong(0),
              summaryDate = c.getString(1),
              bags = deserializeDailySummaryBags(c.getString(2)),
              status = c.getString(3),
              lastError = if (c.isNull(4)) null else c.getString(4)
            )
          )
        }
      }
      out
    }
  }

  suspend fun markDailySummarySyncSuccess(id: Long) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      ensureDailySummarySyncQueueTable(db)
      db.execSQL(
        "UPDATE daily_summary_sync_queue SET status='synced', last_error=NULL, updated_at=? WHERE id=?",
        arrayOf(Instant.now().toString(), id)
      )
    }
  }

  suspend fun markDailySummarySyncError(id: Long, error: String) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      ensureDailySummarySyncQueueTable(db)
      db.execSQL(
        "UPDATE daily_summary_sync_queue SET status='error', last_error=?, updated_at=? WHERE id=?",
        arrayOf(error, Instant.now().toString(), id)
      )
    }
  }


  data class DailySnapshotRow(
    val price: Double?,
    val cogs: Double?,
    val deliveryFee: Double?,
    val hypothesis: String?
  )

  suspend fun getLatestSnapshotForBagColor(
    bagId: String,
    color: String
  ): DailySnapshotRow? = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.rawQuery("PRAGMA table_info(svodka)", null).use { c ->
        val cols = mutableSetOf<String>()
        while (c.moveToNext()) cols.add(c.getString(1))

        if (!cols.contains("delivery_fee")) {
          kotlin.runCatching { db.execSQL("ALTER TABLE svodka ADD COLUMN delivery_fee REAL") }
        }

        if (!cols.contains("hypothesis")) {
          kotlin.runCatching { db.execSQL("ALTER TABLE svodka ADD COLUMN hypothesis TEXT") }
        }
      }

      val row = db.rawQuery(
        """
        SELECT price, cogs, delivery_fee, hypothesis
        FROM svodka
        WHERE bag_id=? AND color=? AND (price IS NOT NULL OR cogs IS NOT NULL OR delivery_fee IS NOT NULL OR hypothesis IS NOT NULL)
        ORDER BY date DESC
        LIMIT 1
        """.trimIndent(),
        arrayOf(bagId, color)
      ).use { c ->
        if (c.moveToFirst()) {
          DailySnapshotRow(
            price = if (c.isNull(0)) null else c.getDouble(0),
            cogs = if (c.isNull(1)) null else c.getDouble(1),
            deliveryFee = if (c.isNull(2)) null else c.getDouble(2),
            hypothesis = if (c.isNull(3)) null else c.getString(3)
          )
        } else null
      }

      row ?: db.rawQuery(
        """
        SELECT price, cogs, delivery_fee, hypothesis
        FROM svodka
        WHERE bag_id=? AND color IN ('__TOTAL__','TOTAL') AND (price IS NOT NULL OR cogs IS NOT NULL OR delivery_fee IS NOT NULL OR hypothesis IS NOT NULL)
        ORDER BY date DESC
        LIMIT 1
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (c.moveToFirst()) {
          DailySnapshotRow(
            price = if (c.isNull(0)) null else c.getDouble(0),
            cogs = if (c.isNull(1)) null else c.getDouble(1),
            deliveryFee = if (c.isNull(2)) null else c.getDouble(2),
            hypothesis = if (c.isNull(3)) null else c.getString(3)
          )
        } else null
      } ?: db.rawQuery(
        """
        SELECT price, cogs, delivery_fee, hypothesis
        FROM bag_user
        WHERE bag_id=?
        LIMIT 1
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (c.moveToFirst()) {
          DailySnapshotRow(
            price = if (c.isNull(0)) null else c.getDouble(0),
            cogs = if (c.isNull(1)) null else c.getDouble(1),
            deliveryFee = if (c.isNull(2)) null else c.getDouble(2),
            hypothesis = if (c.isNull(3)) null else c.getString(3)
          )
        } else null
      }
    }
  }

  suspend fun saveDailySummary(
    date: String,
    bags: List<DailySummaryBagSave>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        db.execSQL(
          "DELETE FROM svodka WHERE date=?",
          arrayOf(date)
        )

        for (bag in bags) {
          val totalOrders = bag.ordersByColor.sumOf { it.second }

          val bagSnapshot = db.rawQuery(
            """
            SELECT hypothesis, price, cogs
            FROM svodka
            WHERE bag_id=? AND color IN ('__TOTAL__','TOTAL')
              AND (price IS NOT NULL OR cogs IS NOT NULL OR hypothesis IS NOT NULL)
            ORDER BY date DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(bag.bagId)
          ).use { c ->
            if (c.moveToFirst()) {
              Triple(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getDouble(1),
                if (c.isNull(2)) null else c.getDouble(2)
              )
            } else {
              Triple<String?, Double?, Double?>(null, null, null)
            }
          }

          val hypothesis = bagSnapshot.first
          val defaultPrice = bagSnapshot.second
          val defaultCogs = bagSnapshot.third

          var weightedPriceSum = 0.0
          var weightedCogsSum = 0.0
          var weightedOrders = 0

          for ((color, orders) in bag.ordersByColor) {
            val svodkaFallback = db.rawQuery(
              """
              SELECT price, cogs
              FROM svodka
              WHERE bag_id=? AND color=?
                AND (price IS NOT NULL OR cogs IS NOT NULL)
              ORDER BY date DESC
              LIMIT 1
              """.trimIndent(),
              arrayOf(bag.bagId, color)
            ).use { c ->
              if (c.moveToFirst()) {
                Pair(
                  if (c.isNull(0)) null else c.getDouble(0),
                  if (c.isNull(1)) null else c.getDouble(1)
                )
              } else {
                Pair<Double?, Double?>(null, null)
              }
            }

            val colorPrice = svodkaFallback.first ?: defaultPrice
            val colorCogs = svodkaFallback.second ?: defaultCogs

            if (orders > 0) {
              if (colorPrice != null) {
                weightedPriceSum += colorPrice * orders
              }
              if (colorCogs != null) {
                weightedCogsSum += colorCogs * orders
              }
              weightedOrders += orders
            }

            db.execSQL(
              """
              INSERT INTO svodka(date, period_start, period_end, bag_id, color, hypothesis, price, orders, source, cogs)
              VALUES(?,?,?,?,?,?,?,?,?,?)
              ON CONFLICT(date, bag_id, color) DO UPDATE SET
                period_start=excluded.period_start,
                period_end=excluded.period_end,
                hypothesis=excluded.hypothesis,
                price=excluded.price,
                orders=excluded.orders,
                source=excluded.source,
                cogs=excluded.cogs
              """.trimIndent(),
              arrayOf(date, date, date, bag.bagId, color, hypothesis, colorPrice, orders.toDouble(), "android-app", colorCogs)
            )
          }

          val totalPrice = when {
            weightedOrders > 0 && weightedPriceSum > 0.0 -> weightedPriceSum / weightedOrders.toDouble()
            else -> defaultPrice
          }

          val cogs = when {
            weightedOrders > 0 && weightedCogsSum > 0.0 -> weightedCogsSum / weightedOrders.toDouble()
            else -> defaultCogs
          }

          db.execSQL(
            """
            INSERT INTO svodka(
              date, period_start, period_end, bag_id, color, hypothesis, price, orders, source,
              rk_spend, rk_impressions, rk_clicks, stake_pct,
              ig_spend, ig_impressions, ig_clicks, cogs
            )
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(date, bag_id, color) DO UPDATE SET
              period_start=excluded.period_start,
              period_end=excluded.period_end,
              hypothesis=excluded.hypothesis,
              price=excluded.price,
              orders=excluded.orders,
              source=excluded.source,
              rk_spend=excluded.rk_spend,
              rk_impressions=excluded.rk_impressions,
              rk_clicks=excluded.rk_clicks,
              stake_pct=excluded.stake_pct,
              ig_spend=excluded.ig_spend,
              ig_impressions=excluded.ig_impressions,
              ig_clicks=excluded.ig_clicks,
              cogs=excluded.cogs
            """.trimIndent(),
            arrayOf(
              date,
              date,
              date,
              bag.bagId,
              "__TOTAL__",
              hypothesis,
              totalPrice,
              totalOrders.toDouble(),
              "android-app",
              if (bag.rkEnabled) bag.rkSpend ?: 0.0 else 0.0,
              if (bag.rkEnabled) (bag.rkImpressions ?: 0L).toDouble() else 0.0,
              if (bag.rkEnabled) (bag.rkClicks ?: 0L).toDouble() else 0.0,
              if (bag.rkEnabled) bag.rkStake ?: 0.0 else 0.0,
              if (bag.igEnabled) bag.igSpend ?: 0.0 else 0.0,
              if (bag.igEnabled) (bag.igImpressions ?: 0L).toDouble() else 0.0,
              if (bag.igEnabled) (bag.igClicks ?: 0L).toDouble() else 0.0,
              cogs
            )
          )
        }

        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

  suspend fun applyRemoteDailySummary(
    date: String,
    entries: List<com.ml.app.data.remote.dto.DailySummaryEntryDto>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        db.execSQL(
          "DELETE FROM svodka WHERE date=? AND source IN ('android-app','remote-sync')",
          arrayOf(date)
        )

        val grouped = entries.groupBy { it.bag_id }

        for ((bagId, bagEntries) in grouped) {
          val bagSnapshot = db.rawQuery(
            """
            SELECT hypothesis, price, cogs
            FROM svodka
            WHERE bag_id=? AND color IN ('__TOTAL__','TOTAL')
              AND (price IS NOT NULL OR cogs IS NOT NULL OR hypothesis IS NOT NULL)
            ORDER BY date DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(bagId)
          ).use { c ->
            if (c.moveToFirst()) {
              Triple(
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getDouble(1),
                if (c.isNull(2)) null else c.getDouble(2)
              )
            } else {
              Triple<String?, Double?, Double?>(null, null, null)
            }
          }

          val totalEntry = bagEntries.firstOrNull { it.color == "__TOTAL__" || it.color == "TOTAL" }

          val hypothesis = totalEntry?.hypothesis ?: bagSnapshot.first
          val defaultPrice = totalEntry?.price ?: bagSnapshot.second
          val defaultCogs = totalEntry?.cogs ?: bagSnapshot.third

          var totalOrders = 0
          var totalRkSpend = 0.0
          var totalRkImpr = 0L
          var totalRkClicks = 0L
          var totalIgSpend = 0.0
          var totalIgImpr = 0L
          var totalIgClicks = 0L
          var totalStake: Double? = null

          for (entry in bagEntries) {
            val colorPrice = entry.price ?: defaultPrice
            val colorCogs = entry.cogs ?: defaultCogs

            if (entry.color != "__TOTAL__" && entry.color != "TOTAL") {
              db.execSQL(
                """
                INSERT INTO svodka(date, period_start, period_end, bag_id, color, hypothesis, price, orders, source, cogs)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(date, bag_id, color) DO UPDATE SET
                  period_start=excluded.period_start,
                  period_end=excluded.period_end,
                  hypothesis=excluded.hypothesis,
                  price=excluded.price,
                  orders=excluded.orders,
                  source=excluded.source,
                  cogs=excluded.cogs
                """.trimIndent(),
                arrayOf(
                  date, date, date, bagId, entry.color, hypothesis,
                  colorPrice, entry.orders.toDouble(), "remote-sync", colorCogs
                )
              )

              totalOrders += entry.orders
            }

            if (entry.color == "__TOTAL__" || entry.color == "TOTAL") {
              totalRkSpend = if (entry.rk_enabled) (entry.rk_spend ?: 0.0) else 0.0
              totalRkImpr = if (entry.rk_enabled) (entry.rk_impressions ?: 0L) else 0L
              totalRkClicks = if (entry.rk_enabled) (entry.rk_clicks ?: 0L) else 0L
              totalIgSpend = if (entry.ig_enabled) (entry.ig_spend ?: 0.0) else 0.0
              totalIgImpr = if (entry.ig_enabled) (entry.ig_impressions ?: 0L) else 0L
              totalIgClicks = if (entry.ig_enabled) (entry.ig_clicks ?: 0L) else 0L
              totalStake = if (entry.rk_enabled) entry.rk_stake else null
            }
          }

          db.execSQL(
            """
            INSERT INTO svodka(
              date, period_start, period_end, bag_id, color, hypothesis, price, orders, source,
              rk_spend, rk_impressions, rk_clicks, stake_pct,
              ig_spend, ig_impressions, ig_clicks, cogs
            )
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(date, bag_id, color) DO UPDATE SET
              period_start=excluded.period_start,
              period_end=excluded.period_end,
              hypothesis=excluded.hypothesis,
              price=excluded.price,
              orders=excluded.orders,
              source=excluded.source,
              rk_spend=excluded.rk_spend,
              rk_impressions=excluded.rk_impressions,
              rk_clicks=excluded.rk_clicks,
              stake_pct=excluded.stake_pct,
              ig_spend=excluded.ig_spend,
              ig_impressions=excluded.ig_impressions,
              ig_clicks=excluded.ig_clicks,
              cogs=excluded.cogs
            """.trimIndent(),
            arrayOf(
              date, date, date, bagId, "__TOTAL__", hypothesis, defaultPrice,
              totalOrders.toDouble(), "remote-sync",
              totalRkSpend, totalRkImpr.toDouble(), totalRkClicks.toDouble(), totalStake ?: 0.0,
              totalIgSpend, totalIgImpr.toDouble(), totalIgClicks.toDouble(), defaultCogs
            )
          )
        }

        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

}
