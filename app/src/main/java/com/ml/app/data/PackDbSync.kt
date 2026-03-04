package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

object PackDbSync {

  private const val USER_TABLE = "bag_card_type"

  fun mergedDbFile(ctx: Context): File {
    val dir = File(ctx.filesDir, "local_pack")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "data.sqlite")
  }

  fun dbFileToUse(ctx: Context): File {
    val merged = mergedDbFile(ctx)
    return if (merged.exists() && merged.length() > 0) merged else PackPaths.dbFile(ctx)
  }

  /**
   * Call after pack was (downloaded/updated) and unpacked into PackPaths.packDir(ctx).
   * Creates/updates merged DB:
   *   - take fresh pack db
   *   - merge user tables from previous merged db
   *   - atomically replace merged db
   */
  fun refreshMergedDb(ctx: Context) {
    val packDb = PackPaths.dbFile(ctx)
    if (!packDb.exists() || packDb.length() == 0L) return

    val merged = mergedDbFile(ctx)
    val tmp = File(merged.parentFile, "data.sqlite.tmp")

    // copy fresh pack db into tmp
    packDb.copyTo(tmp, overwrite = true)

    // merge user tables from old merged -> tmp
    if (merged.exists() && merged.length() > 0L) {
      mergeUserTables(fromDbFile = merged, toDbFile = tmp)
    } else {
      // ensure user tables exist in tmp even on first run
      ensureUserTables(tmp)
    }

    // replace merged atomically
    if (merged.exists()) merged.delete()
    tmp.renameTo(merged)
  }

  private fun ensureUserTables(dbFile: File) {
    SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
      db.beginTransaction()
      try {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS $USER_TABLE(
            bag_id TEXT PRIMARY KEY,
            type TEXT NOT NULL
          );
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${USER_TABLE}_type ON $USER_TABLE(type);")
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

  private fun mergeUserTables(fromDbFile: File, toDbFile: File) {
    val fromDb = SQLiteDatabase.openDatabase(fromDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val toDb = SQLiteDatabase.openDatabase(toDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    try {
      toDb.beginTransaction()
      try {
        // create table in target if missing
        toDb.execSQL(
          """
          CREATE TABLE IF NOT EXISTS $USER_TABLE(
            bag_id TEXT PRIMARY KEY,
            type TEXT NOT NULL
          );
          """.trimIndent()
        )
        toDb.execSQL("CREATE INDEX IF NOT EXISTS idx_${USER_TABLE}_type ON $USER_TABLE(type);")

        // if source doesn't have table -> nothing to merge
        val has = fromDb.rawQuery(
          "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
          arrayOf(USER_TABLE)
        ).use { c -> c.moveToFirst() }

        if (has) {
          fromDb.rawQuery("SELECT bag_id, type FROM $USER_TABLE", null).use { c ->
            val iId = c.getColumnIndexOrThrow("bag_id")
            val iType = c.getColumnIndexOrThrow("type")
            while (c.moveToNext()) {
              val id = c.getString(iId)
              val type = c.getString(iType)
              // upsert
              toDb.execSQL(
                "INSERT INTO $USER_TABLE(bag_id,type) VALUES(?,?) " +
                  "ON CONFLICT(bag_id) DO UPDATE SET type=excluded.type",
                arrayOf(id, type)
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
