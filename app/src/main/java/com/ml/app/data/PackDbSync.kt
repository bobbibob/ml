package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

object PackDbSync {

  private const val T_CARD_TYPE = "bag_card_type"
  private const val T_BAG_USER = "bag_user"
  private const val T_BAG_USER_COLORS = "bag_user_colors"
  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"
  private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"
  private const val T_CARD_COLOR_SKU = "card_color_sku"
  private const val T_SERVER_CARD_OVERRIDES = "server_card_overrides"

  fun mergedDbFile(ctx: Context): File {
    val dir = File(ctx.filesDir, "local_pack")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "data.sqlite")
  }

  fun dbFileToUse(ctx: Context): File {
    val merged = mergedDbFile(ctx)
    return if (merged.exists() && merged.length() > 0) merged else PackPaths.dbFile(ctx)
  }

  fun refreshMergedDb(ctx: Context) {
    val packDb = PackPaths.dbFile(ctx)
    if (!packDb.exists() || packDb.length() == 0L) return

    val merged = mergedDbFile(ctx)
    val tmp = File(merged.parentFile, "data.sqlite.tmp")

    packDb.copyTo(tmp, overwrite = true)

    if (merged.exists() && merged.length() > 0L) {
      mergeUserTables(fromDbFile = merged, toDbFile = tmp)
    } else {
      // first run: just ensure tables in tmp
      SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.beginTransaction()
        try {
          ensureUserTables(db)
          db.setTransactionSuccessful()
        } finally {
          db.endTransaction()
        }
      }
    }

    if (merged.exists()) merged.delete()
    tmp.renameTo(merged)
  }

  private fun ensureUserTables(db: SQLiteDatabase) {
    // 1) card type
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_CARD_TYPE(
        bag_id TEXT PRIMARY KEY,
        type TEXT NOT NULL
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_${T_CARD_TYPE}_type ON $T_CARD_TYPE(type);")

    // 2) bag user main
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_USER(
        bag_id TEXT PRIMARY KEY,
        name TEXT,
        hypothesis TEXT,
        price REAL,
        cogs REAL,
        card_type TEXT,
        photo_path TEXT
      );
      """.trimIndent()
    )

    // 3) bag user colors
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_USER_COLORS(
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        PRIMARY KEY(bag_id, color)
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_${T_BAG_USER_COLORS}_bag ON $T_BAG_USER_COLORS(bag_id);")

    // 4) bag user color prices
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_USER_COLOR_PRICE(
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        price REAL,
        PRIMARY KEY(bag_id, color)
      );
      """.trimIndent()
    )

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_STOCK_OVERRIDE(
        effective_date TEXT NOT NULL,
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        stock REAL NOT NULL,
        PRIMARY KEY(effective_date, bag_id, color)
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_date_bag_color ON $T_BAG_STOCK_OVERRIDE(effective_date, bag_id, color)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_bag_color_date ON $T_BAG_STOCK_OVERRIDE(bag_id, color, effective_date)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_${T_BAG_USER_COLOR_PRICE}_bag ON $T_BAG_USER_COLOR_PRICE(bag_id);")

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_CARD_COLOR_SKU(
        card_name TEXT,
        color TEXT,
        sku TEXT,
        article_id TEXT,
        PRIMARY KEY(card_name, color)
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_${T_CARD_COLOR_SKU}_card ON $T_CARD_COLOR_SKU(card_name);")

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_SERVER_CARD_OVERRIDES(
        bag_id TEXT PRIMARY KEY,
        name TEXT,
        hypothesis TEXT,
        price REAL,
        cogs REAL,
        delivery_fee REAL,
        card_type TEXT,
        photo_path TEXT,
        colors_json TEXT,
        color_prices_json TEXT,
        sku_links_json TEXT,
        updated_at TEXT NOT NULL
      );
      """.trimIndent()
    )
  }


  private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
    return db.rawQuery("PRAGMA table_info(" + table + ")", null).use { c ->
      val idx = c.getColumnIndex("name")
      while (c.moveToNext()) {
        if (idx >= 0 && c.getString(idx) == column) return@use true
      }
      false
    }
  }

  private fun normalizeSvodkaSchema(db: SQLiteDatabase) {
    if (!tableExists(db, "svodka")) return

    val hasBagId = columnExists(db, "svodka", "bag_id")
    val hasBag = columnExists(db, "svodka", "bag")

    if (!hasBagId && hasBag) {
      db.execSQL("ALTER TABLE svodka ADD COLUMN bag_id TEXT")
      db.execSQL("UPDATE svodka SET bag_id = bag WHERE bag_id IS NULL OR bag_id = ''")
    }

    if (columnExists(db, "svodka", "bag_id")) {
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_bag_id ON svodka(bag_id)")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_date_bag_id_color ON svodka(date, bag_id, color)")
    }
  }

  private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
    return db.rawQuery(
      "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
      arrayOf(name)
    ).use { c -> c.moveToFirst() }
  }

  private fun mergeLocalSvodkaRows(fromDb: SQLiteDatabase, toDb: SQLiteDatabase) {
    if (!tableExists(fromDb, "svodka") || !tableExists(toDb, "svodka")) return

    kotlin.runCatching { toDb.execSQL("ALTER TABLE svodka ADD COLUMN delivery_fee REAL") }

    fromDb.rawQuery(
      """
      SELECT
        date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
        rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc, stake_pct,
        ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
        cogs, profit_net, roi_pct, notes, updated_at, delivery_fee
      FROM svodka
      WHERE source IN ('android-app','remote-sync')
      """.trimIndent(),
      null
    ).use { c ->
      val iDate = c.getColumnIndexOrThrow("date")
      val iPeriodStart = c.getColumnIndexOrThrow("period_start")
      val iPeriodEnd = c.getColumnIndexOrThrow("period_end")
      val iBagId = c.getColumnIndexOrThrow("bag_id")
      val iColor = c.getColumnIndexOrThrow("color")
      val iSource = c.getColumnIndexOrThrow("source")
      val iHyp = c.getColumnIndexOrThrow("hypothesis")
      val iPrice = c.getColumnIndexOrThrow("price")
      val iOrders = c.getColumnIndexOrThrow("orders")
      val iStock = c.getColumnIndexOrThrow("stock")
      val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
      val iRkImpr = c.getColumnIndexOrThrow("rk_impressions")
      val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")
      val iRkCtr = c.getColumnIndexOrThrow("rk_ctr")
      val iRkCpc = c.getColumnIndexOrThrow("rk_cpc")
      val iStake = c.getColumnIndexOrThrow("stake_pct")
      val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
      val iIgImpr = c.getColumnIndexOrThrow("ig_impressions")
      val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")
      val iIgCtr = c.getColumnIndexOrThrow("ig_ctr")
      val iIgCpc = c.getColumnIndexOrThrow("ig_cpc")
      val iCogs = c.getColumnIndexOrThrow("cogs")
      val iProfit = c.getColumnIndexOrThrow("profit_net")
      val iRoi = c.getColumnIndexOrThrow("roi_pct")
      val iNotes = c.getColumnIndexOrThrow("notes")
      val iUpdatedAt = c.getColumnIndexOrThrow("updated_at")
      val iDelivery = kotlin.runCatching { c.getColumnIndexOrThrow("delivery_fee") }.getOrDefault(-1)

      while (c.moveToNext()) {
        toDb.execSQL(
          """
          INSERT INTO svodka(
            date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
            rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc, stake_pct,
            ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
            cogs, profit_net, roi_pct, notes, updated_at, delivery_fee
          )
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(date, bag_id, color) DO UPDATE SET
            period_start=excluded.period_start,
            period_end=excluded.period_end,
            source=excluded.source,
            hypothesis=excluded.hypothesis,
            price=excluded.price,
            orders=excluded.orders,
            stock=excluded.stock,
            rk_spend=excluded.rk_spend,
            rk_impressions=excluded.rk_impressions,
            rk_clicks=excluded.rk_clicks,
            rk_ctr=excluded.rk_ctr,
            rk_cpc=excluded.rk_cpc,
            stake_pct=excluded.stake_pct,
            ig_spend=excluded.ig_spend,
            ig_impressions=excluded.ig_impressions,
            ig_clicks=excluded.ig_clicks,
            ig_ctr=excluded.ig_ctr,
            ig_cpc=excluded.ig_cpc,
            cogs=excluded.cogs,
            profit_net=excluded.profit_net,
            roi_pct=excluded.roi_pct,
            notes=excluded.notes,
            updated_at=excluded.updated_at,
            delivery_fee=excluded.delivery_fee
          """.trimIndent(),
          arrayOf(
            c.getString(iDate),
            if (c.isNull(iPeriodStart)) null else c.getString(iPeriodStart),
            if (c.isNull(iPeriodEnd)) null else c.getString(iPeriodEnd),
            c.getString(iBagId),
            c.getString(iColor),
            if (c.isNull(iSource)) null else c.getString(iSource),
            if (c.isNull(iHyp)) null else c.getString(iHyp),
            if (c.isNull(iPrice)) null else c.getDouble(iPrice),
            if (c.isNull(iOrders)) null else c.getDouble(iOrders),
            if (c.isNull(iStock)) null else c.getDouble(iStock),
            if (c.isNull(iRkSpend)) null else c.getDouble(iRkSpend),
            if (c.isNull(iRkImpr)) null else c.getDouble(iRkImpr),
            if (c.isNull(iRkClicks)) null else c.getDouble(iRkClicks),
            if (c.isNull(iRkCtr)) null else c.getDouble(iRkCtr),
            if (c.isNull(iRkCpc)) null else c.getDouble(iRkCpc),
            if (c.isNull(iStake)) null else c.getDouble(iStake),
            if (c.isNull(iIgSpend)) null else c.getDouble(iIgSpend),
            if (c.isNull(iIgImpr)) null else c.getDouble(iIgImpr),
            if (c.isNull(iIgClicks)) null else c.getDouble(iIgClicks),
            if (c.isNull(iIgCtr)) null else c.getDouble(iIgCtr),
            if (c.isNull(iIgCpc)) null else c.getDouble(iIgCpc),
            if (c.isNull(iCogs)) null else c.getDouble(iCogs),
            if (c.isNull(iProfit)) null else c.getDouble(iProfit),
            if (c.isNull(iRoi)) null else c.getDouble(iRoi),
            if (c.isNull(iNotes)) null else c.getString(iNotes),
            if (c.isNull(iUpdatedAt)) null else c.getString(iUpdatedAt),
            if (iDelivery == -1 || c.isNull(iDelivery)) null else c.getDouble(iDelivery)
          )
        )
      }
    }
  }


  private fun mergeUserTables(fromDbFile: File, toDbFile: File) {
    val fromDb = SQLiteDatabase.openDatabase(fromDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val toDb = SQLiteDatabase.openDatabase(toDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    try {
      toDb.beginTransaction()
      try {
        // IMPORTANT: use SAME connection (no nested openDatabase)
        ensureUserTables(toDb)
        normalizeSvodkaSchema(toDb)

        // merge bag_card_type
        if (tableExists(fromDb, T_CARD_TYPE)) {
          fromDb.rawQuery("SELECT bag_id, type FROM $T_CARD_TYPE", null).use { c ->
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iType = c.getColumnIndexOrThrow("type")
            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT INTO $T_CARD_TYPE(bag_id,type) VALUES(?,?) " +
                  "ON CONFLICT(bag_id) DO UPDATE SET type=excluded.type",
                arrayOf(c.getString(iId), c.getString(iType))
              )
            }
          }
        }

        // merge bag_user
        if (tableExists(fromDb, T_BAG_USER)) {
          fromDb.rawQuery(
            "SELECT bag_id, name, hypothesis, price, cogs, card_type, photo_path FROM $T_BAG_USER",
            null
          ).use { c ->
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iName = c.getColumnIndexOrThrow("name")
            val iHyp = c.getColumnIndexOrThrow("hypothesis")
            val iPrice = c.getColumnIndexOrThrow("price")
            val iCogs = c.getColumnIndexOrThrow("cogs")
            val iCt = c.getColumnIndexOrThrow("card_type")
            val iPhoto = c.getColumnIndexOrThrow("photo_path")
            while (c.moveToNext()) {
              val id = c.getString(iId)
              val name = if (c.isNull(iName)) null else c.getString(iName)
              val hyp = if (c.isNull(iHyp)) null else c.getString(iHyp)
              val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
              val cogs = if (c.isNull(iCogs)) null else c.getDouble(iCogs)
              val ct = if (c.isNull(iCt)) null else c.getString(iCt)
              val photo = if (c.isNull(iPhoto)) null else c.getString(iPhoto)

              toDb.execSQL(
                "INSERT INTO $T_BAG_USER(bag_id,name,hypothesis,price,cogs,card_type,photo_path) VALUES(?,?,?,?,?,?,?) " +
                  "ON CONFLICT(bag_id) DO UPDATE SET " +
                  "name=excluded.name, hypothesis=excluded.hypothesis, price=excluded.price, cogs=excluded.cogs, " +
                  "card_type=excluded.card_type, photo_path=excluded.photo_path",
                arrayOf(id, name, hyp, price, cogs, ct, photo)
              )
            }
          }
        }

        // merge bag_user_colors
        if (tableExists(fromDb, T_BAG_USER_COLORS)) {
          fromDb.rawQuery("SELECT bag_id, color FROM $T_BAG_USER_COLORS", null).use { c ->
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iColor = c.getColumnIndexOrThrow("color")
            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT OR IGNORE INTO $T_BAG_USER_COLORS(bag_id,color) VALUES(?,?)",
                arrayOf(c.getString(iId), c.getString(iColor))
              )
            }
          }
        }

        // merge bag_user_color_price
        if (tableExists(fromDb, T_BAG_USER_COLOR_PRICE)) {
          fromDb.rawQuery("SELECT bag_id, color, price FROM $T_BAG_USER_COLOR_PRICE", null).use { c ->
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iColor = c.getColumnIndexOrThrow("color")
            val iPrice = c.getColumnIndexOrThrow("price")
            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT INTO $T_BAG_USER_COLOR_PRICE(bag_id,color,price) VALUES(?,?,?) " +
                  "ON CONFLICT(bag_id,color) DO UPDATE SET price=excluded.price",
                arrayOf(
                  c.getString(iId),
                  c.getString(iColor),
                  if (c.isNull(iPrice)) null else c.getDouble(iPrice)
                )
              )
            }
          }
        }

        if (tableExists(fromDb, T_BAG_STOCK_OVERRIDE)) {
          fromDb.rawQuery("SELECT effective_date, bag_id, color, stock FROM $T_BAG_STOCK_OVERRIDE", null).use { c ->
            val iDate = c.getColumnIndexOrThrow("effective_date")
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iColor = c.getColumnIndexOrThrow("color")
            val iStock = c.getColumnIndexOrThrow("stock")
            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT INTO $T_BAG_STOCK_OVERRIDE(effective_date,bag_id,color,stock) VALUES(?,?,?,?) " +
                  "ON CONFLICT(effective_date,bag_id,color) DO UPDATE SET stock=excluded.stock",
                arrayOf(
                  c.getString(iDate),
                  c.getString(iId),
                  c.getString(iColor),
                  c.getDouble(iStock)
                )
              )
            }
          }
        }

        if (tableExists(fromDb, T_CARD_COLOR_SKU)) {
          fromDb.rawQuery("SELECT card_name, color, sku, article_id FROM $T_CARD_COLOR_SKU", null).use { c ->
            val iCard = c.getColumnIndexOrThrow("card_name")
            val iColor = c.getColumnIndexOrThrow("color")
            val iSku = c.getColumnIndexOrThrow("sku")
            val iArticle = c.getColumnIndexOrThrow("article_id")
            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT INTO $T_CARD_COLOR_SKU(card_name,color,sku,article_id) VALUES(?,?,?,?) " +
                  "ON CONFLICT(card_name,color) DO UPDATE SET sku=excluded.sku, article_id=excluded.article_id",
                arrayOf(
                  c.getString(iCard),
                  c.getString(iColor),
                  c.getString(iSku),
                  if (c.isNull(iArticle)) null else c.getString(iArticle)
                )
              )
            }
          }
        }

        if (tableExists(fromDb, T_SERVER_CARD_OVERRIDES)) {
          fromDb.rawQuery(
            "SELECT bag_id, name, hypothesis, price, cogs, delivery_fee, card_type, photo_path, colors_json, color_prices_json, sku_links_json, updated_at FROM $T_SERVER_CARD_OVERRIDES",
            null
          ).use { c ->
            val iBagId = c.getColumnIndexOrThrow("bag_id")
            val iName = c.getColumnIndexOrThrow("name")
            val iHyp = c.getColumnIndexOrThrow("hypothesis")
            val iPrice = c.getColumnIndexOrThrow("price")
            val iCogs = c.getColumnIndexOrThrow("cogs")
            val iDelivery = c.getColumnIndexOrThrow("delivery_fee")
            val iCardType = c.getColumnIndexOrThrow("card_type")
            val iPhoto = c.getColumnIndexOrThrow("photo_path")
            val iColors = c.getColumnIndexOrThrow("colors_json")
            val iColorPrices = c.getColumnIndexOrThrow("color_prices_json")
            val iSkuLinks = c.getColumnIndexOrThrow("sku_links_json")
            val iUpdatedAt = c.getColumnIndexOrThrow("updated_at")

            while (c.moveToNext()) {
              toDb.execSQL(
                "INSERT INTO $T_SERVER_CARD_OVERRIDES(bag_id,name,hypothesis,price,cogs,delivery_fee,card_type,photo_path,colors_json,color_prices_json,sku_links_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) " +
                  "ON CONFLICT(bag_id) DO UPDATE SET " +
                  "name=excluded.name, hypothesis=excluded.hypothesis, price=excluded.price, cogs=excluded.cogs, " +
                  "delivery_fee=excluded.delivery_fee, card_type=excluded.card_type, photo_path=excluded.photo_path, " +
                  "colors_json=excluded.colors_json, color_prices_json=excluded.color_prices_json, sku_links_json=excluded.sku_links_json, updated_at=excluded.updated_at",
                arrayOf(
                  c.getString(iBagId),
                  if (c.isNull(iName)) null else c.getString(iName),
                  if (c.isNull(iHyp)) null else c.getString(iHyp),
                  if (c.isNull(iPrice)) null else c.getDouble(iPrice),
                  if (c.isNull(iCogs)) null else c.getDouble(iCogs),
                  if (c.isNull(iDelivery)) null else c.getDouble(iDelivery),
                  if (c.isNull(iCardType)) null else c.getString(iCardType),
                  if (c.isNull(iPhoto)) null else c.getString(iPhoto),
                  if (c.isNull(iColors)) null else c.getString(iColors),
                  if (c.isNull(iColorPrices)) null else c.getString(iColorPrices),
                  if (c.isNull(iSkuLinks)) null else c.getString(iSkuLinks),
                  c.getString(iUpdatedAt)
                )
              )
            }
          }
        }
        if (tableExists(fromDb, "svodka") && tableExists(toDb, "svodka")) {
          kotlin.runCatching { toDb.execSQL("ALTER TABLE svodka ADD COLUMN delivery_fee REAL") }

          fromDb.rawQuery(
            """
            SELECT
              date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
              rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc, stake_pct,
              ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
              cogs, profit_net, roi_pct, notes, updated_at, delivery_fee
            FROM svodka
            WHERE source IN ('android-app','remote-sync')
            """.trimIndent(),
            null
          ).use { c ->
            val iDate = c.getColumnIndexOrThrow("date")
            val iPeriodStart = c.getColumnIndexOrThrow("period_start")
            val iPeriodEnd = c.getColumnIndexOrThrow("period_end")
            val iBagId = c.getColumnIndexOrThrow("bag_id")
            val iColor = c.getColumnIndexOrThrow("color")
            val iSource = c.getColumnIndexOrThrow("source")
            val iHyp = c.getColumnIndexOrThrow("hypothesis")
            val iPrice = c.getColumnIndexOrThrow("price")
            val iOrders = c.getColumnIndexOrThrow("orders")
            val iStock = c.getColumnIndexOrThrow("stock")
            val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
            val iRkImpr = c.getColumnIndexOrThrow("rk_impressions")
            val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")
            val iRkCtr = c.getColumnIndexOrThrow("rk_ctr")
            val iRkCpc = c.getColumnIndexOrThrow("rk_cpc")
            val iStake = c.getColumnIndexOrThrow("stake_pct")
            val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
            val iIgImpr = c.getColumnIndexOrThrow("ig_impressions")
            val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")
            val iIgCtr = c.getColumnIndexOrThrow("ig_ctr")
            val iIgCpc = c.getColumnIndexOrThrow("ig_cpc")
            val iCogs = c.getColumnIndexOrThrow("cogs")
            val iProfit = c.getColumnIndexOrThrow("profit_net")
            val iRoi = c.getColumnIndexOrThrow("roi_pct")
            val iNotes = c.getColumnIndexOrThrow("notes")
            val iUpdatedAt = c.getColumnIndexOrThrow("updated_at")
            val iDelivery = kotlin.runCatching { c.getColumnIndexOrThrow("delivery_fee") }.getOrDefault(-1)

            while (c.moveToNext()) {
              toDb.execSQL(
                """
                INSERT INTO svodka(
                  date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
                  rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc, stake_pct,
                  ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
                  cogs, profit_net, roi_pct, notes, updated_at, delivery_fee
                )
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(date, bag_id, color) DO UPDATE SET
                  period_start=excluded.period_start,
                  period_end=excluded.period_end,
                  source=excluded.source,
                  hypothesis=excluded.hypothesis,
                  price=excluded.price,
                  orders=excluded.orders,
                  stock=excluded.stock,
                  rk_spend=excluded.rk_spend,
                  rk_impressions=excluded.rk_impressions,
                  rk_clicks=excluded.rk_clicks,
                  rk_ctr=excluded.rk_ctr,
                  rk_cpc=excluded.rk_cpc,
                  stake_pct=excluded.stake_pct,
                  ig_spend=excluded.ig_spend,
                  ig_impressions=excluded.ig_impressions,
                  ig_clicks=excluded.ig_clicks,
                  ig_ctr=excluded.ig_ctr,
                  ig_cpc=excluded.ig_cpc,
                  cogs=excluded.cogs,
                  profit_net=excluded.profit_net,
                  roi_pct=excluded.roi_pct,
                  notes=excluded.notes,
                  updated_at=excluded.updated_at,
                  delivery_fee=excluded.delivery_fee
                """.trimIndent(),
                arrayOf(
                  c.getString(iDate),
                  if (c.isNull(iPeriodStart)) null else c.getString(iPeriodStart),
                  if (c.isNull(iPeriodEnd)) null else c.getString(iPeriodEnd),
                  c.getString(iBagId),
                  c.getString(iColor),
                  if (c.isNull(iSource)) null else c.getString(iSource),
                  if (c.isNull(iHyp)) null else c.getString(iHyp),
                  if (c.isNull(iPrice)) null else c.getDouble(iPrice),
                  if (c.isNull(iOrders)) null else c.getDouble(iOrders),
                  if (c.isNull(iStock)) null else c.getDouble(iStock),
                  if (c.isNull(iRkSpend)) null else c.getDouble(iRkSpend),
                  if (c.isNull(iRkImpr)) null else c.getDouble(iRkImpr),
                  if (c.isNull(iRkClicks)) null else c.getDouble(iRkClicks),
                  if (c.isNull(iRkCtr)) null else c.getDouble(iRkCtr),
                  if (c.isNull(iRkCpc)) null else c.getDouble(iRkCpc),
                  if (c.isNull(iStake)) null else c.getDouble(iStake),
                  if (c.isNull(iIgSpend)) null else c.getDouble(iIgSpend),
                  if (c.isNull(iIgImpr)) null else c.getDouble(iIgImpr),
                  if (c.isNull(iIgClicks)) null else c.getDouble(iIgClicks),
                  if (c.isNull(iIgCtr)) null else c.getDouble(iIgCtr),
                  if (c.isNull(iIgCpc)) null else c.getDouble(iIgCpc),
                  if (c.isNull(iCogs)) null else c.getDouble(iCogs),
                  if (c.isNull(iProfit)) null else c.getDouble(iProfit),
                  if (c.isNull(iRoi)) null else c.getDouble(iRoi),
                  if (c.isNull(iNotes)) null else c.getString(iNotes),
                  if (c.isNull(iUpdatedAt)) null else c.getString(iUpdatedAt),
                  if (iDelivery == -1 || c.isNull(iDelivery)) null else c.getDouble(iDelivery)
                )
              )
            }
          }
        }


        mergeLocalSvodkaRows(fromDb, toDb)
        toDb.setTransactionSuccessful()
      } finally {
        toDb.endTransaction()
      }
    } finally {
      fromDb.close()
      toDb.close()
    }
  }
}
