package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

object PackDbSync {

  private const val T_CARD_TYPE = "bag_card_type"
  private const val T_BAG_USER = "bag_user"
  private const val T_BAG_USER_COLORS = "bag_user_colors"
  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"

  fun mergedDbFile(ctx: Context): File {
    val dir = File(ctx.filesDir, "local_pack")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "data.sqlite")
  }

  fun dbFileToUse(ctx: Context): File {
    return PackPaths.dbFile(ctx)
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
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_${T_BAG_USER_COLOR_PRICE}_bag ON $T_BAG_USER_COLOR_PRICE(bag_id);")
  }

  private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
    return db.rawQuery(
      "SELECT 1 FROM sqlite_master WHERE type=table AND name=? LIMIT 1",
      arrayOf(name)
    ).use { c -> c.moveToFirst() }
  }

  private fun mergeUserTables(fromDbFile: File, toDbFile: File) {
    val fromDb = SQLiteDatabase.openDatabase(fromDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val toDb = SQLiteDatabase.openDatabase(toDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    try {
      toDb.beginTransaction()
      try {
        // IMPORTANT: use SAME connection (no nested openDatabase)
        ensureUserTables(toDb)

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
