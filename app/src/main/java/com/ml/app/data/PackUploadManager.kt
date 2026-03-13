package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PackUploadManager {

  private const val T_CARD_TYPE = "bag_card_type"
  private const val T_BAG_USER = "bag_user"
  private const val T_BAG_USER_COLORS = "bag_user_colors"
  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"
  private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"

  suspend fun saveUserChangesAndUpload(context: Context) = withContext(Dispatchers.IO) {
    val packDir = PackPaths.packDir(context)
    val packDb = PackPaths.dbFile(context)
    val mergedDb = PackDbSync.mergedDbFile(context)
    val manifestFile = PackPaths.manifestFile(context)

    require(packDir.exists()) { "Не найден packDir" }
    require(packDb.exists()) { "Не найден data.sqlite пакета" }
    require(mergedDb.exists()) { "Не найдена локальная merged db" }

    val localManifest = if (manifestFile.exists()) JSONObject(manifestFile.readText()) else JSONObject()
    val localVersion = localManifest.optInt("version", 0)

    val remoteTmpDir = File(context.cacheDir, "pack_remote_check")
    if (remoteTmpDir.exists()) remoteTmpDir.deleteRecursively()
    remoteTmpDir.mkdirs()

    val r2 = R2Client(context)
    val remoteZip = r2.downloadPackZip()
    kotlin.runCatching { ZipUtil.unzipToDir(remoteZip, remoteTmpDir) }

    val remoteManifestFile = File(remoteTmpDir, "manifest.json")
    val remoteVersion = if (remoteManifestFile.exists()) {
      kotlin.runCatching { JSONObject(remoteManifestFile.readText()).optInt("version", 0) }.getOrDefault(0)
    } else {
      localVersion
    }

    if (remoteVersion > localVersion) {
      throw IllegalStateException("Сначала обнови пакет")
    }

    mergeUserTables(fromDbFile = mergedDb, toDbFile = packDb)

    val updatedManifest = if (manifestFile.exists()) JSONObject(manifestFile.readText()) else JSONObject()
    updatedManifest.put("version", localVersion + 1)
    updatedManifest.put("updated_at", Instant.now().toString())
    if (!updatedManifest.has("updated_by")) updatedManifest.put("updated_by", "android-app")
    updatedManifest.put("db_hash", sha256File(packDb))

    val imagesDir = File(packDir, "images")
    val imagesIndex = buildImagesIndex(imagesDir)
    updatedManifest.put("images_index", imagesIndex)
    updatedManifest.put("images_hash", sha256String(buildImagesCanonical(imagesIndex)))

    manifestFile.writeText(updatedManifest.toString(2))

    val zipFile = File(context.cacheDir, "database_pack_upload.zip")
    if (zipFile.exists()) zipFile.delete()
    zipDirectory(packDir, zipFile)

    r2.uploadPackZip(zipFile)

    kotlin.runCatching {
      val remote = r2.headPack()
      context.getSharedPreferences("ml_pack", Context.MODE_PRIVATE)
        .edit()
        .putString("pack_etag", remote.etag)
        .apply()
    }

    PackDbSync.refreshMergedDb(context)
  }

  private fun ensureUserTables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_CARD_TYPE(
        bag_id TEXT PRIMARY KEY,
        type TEXT NOT NULL
      );
      """.trimIndent()
    )

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

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_USER_COLORS(
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        PRIMARY KEY(bag_id, color)
      );
      """.trimIndent()
    )

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
  }

  private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
    return db.rawQuery(
      "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
      arrayOf(name)
    ).use { it.moveToFirst() }
  }

  private fun mergeUserTables(fromDbFile: File, toDbFile: File) {
    val fromDb = SQLiteDatabase.openDatabase(fromDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val toDb = SQLiteDatabase.openDatabase(toDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    try {
      toDb.beginTransaction()
      try {
        ensureUserTables(toDb)

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
              toDb.execSQL(
                "INSERT INTO $T_BAG_USER(bag_id,name,hypothesis,price,cogs,card_type,photo_path) VALUES(?,?,?,?,?,?,?) " +
                  "ON CONFLICT(bag_id) DO UPDATE SET " +
                  "name=excluded.name, hypothesis=excluded.hypothesis, price=excluded.price, cogs=excluded.cogs, " +
                  "card_type=excluded.card_type, photo_path=excluded.photo_path",
                arrayOf(
                  c.getString(iId),
                  if (c.isNull(iName)) null else c.getString(iName),
                  if (c.isNull(iHyp)) null else c.getString(iHyp),
                  if (c.isNull(iPrice)) null else c.getDouble(iPrice),
                  if (c.isNull(iCogs)) null else c.getDouble(iCogs),
                  if (c.isNull(iCt)) null else c.getString(iCt),
                  if (c.isNull(iPhoto)) null else c.getString(iPhoto)
                )
              )
            }
          }
        }

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

        if (tableExists(fromDb, "svodka") && tableExists(toDb, "svodka")) {
          fromDb.rawQuery(
            """
            SELECT
              date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
              rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc,
              ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
              stake_pct, cr_pct, views, clicks, ctr, cpc, cpo,
              cogs, profit_net, roi_pct, notes, updated_at
            FROM svodka
            WHERE source='android-app'
            """.trimIndent(),
            null
          ).use { c ->
            val iDate = c.getColumnIndexOrThrow("date")
            val iPeriodStart = c.getColumnIndexOrThrow("period_start")
            val iPeriodEnd = c.getColumnIndexOrThrow("period_end")
            val iBagId = c.getColumnIndexOrThrow("bag_id")
            val iColor = c.getColumnIndexOrThrow("color")
            val iSource = c.getColumnIndexOrThrow("source")
            val iHypothesis = c.getColumnIndexOrThrow("hypothesis")
            val iPrice = c.getColumnIndexOrThrow("price")
            val iOrders = c.getColumnIndexOrThrow("orders")
            val iStock = c.getColumnIndexOrThrow("stock")
            val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
            val iRkImp = c.getColumnIndexOrThrow("rk_impressions")
            val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")
            val iRkCtr = c.getColumnIndexOrThrow("rk_ctr")
            val iRkCpc = c.getColumnIndexOrThrow("rk_cpc")
            val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
            val iIgImp = c.getColumnIndexOrThrow("ig_impressions")
            val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")
            val iIgCtr = c.getColumnIndexOrThrow("ig_ctr")
            val iIgCpc = c.getColumnIndexOrThrow("ig_cpc")
            val iStake = c.getColumnIndexOrThrow("stake_pct")
            val iCr = c.getColumnIndexOrThrow("cr_pct")
            val iViews = c.getColumnIndexOrThrow("views")
            val iClicks = c.getColumnIndexOrThrow("clicks")
            val iCtr = c.getColumnIndexOrThrow("ctr")
            val iCpc = c.getColumnIndexOrThrow("cpc")
            val iCpo = c.getColumnIndexOrThrow("cpo")
            val iCogs = c.getColumnIndexOrThrow("cogs")
            val iProfit = c.getColumnIndexOrThrow("profit_net")
            val iRoi = c.getColumnIndexOrThrow("roi_pct")
            val iNotes = c.getColumnIndexOrThrow("notes")
            val iUpdatedAt = c.getColumnIndexOrThrow("updated_at")

            while (c.moveToNext()) {
              toDb.execSQL(
                """
                INSERT INTO svodka(
                  date, period_start, period_end, bag_id, color, source, hypothesis, price, orders, stock,
                  rk_spend, rk_impressions, rk_clicks, rk_ctr, rk_cpc,
                  ig_spend, ig_impressions, ig_clicks, ig_ctr, ig_cpc,
                  stake_pct, cr_pct, views, clicks, ctr, cpc, cpo,
                  cogs, profit_net, roi_pct, notes, updated_at
                )
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                  ig_spend=excluded.ig_spend,
                  ig_impressions=excluded.ig_impressions,
                  ig_clicks=excluded.ig_clicks,
                  ig_ctr=excluded.ig_ctr,
                  ig_cpc=excluded.ig_cpc,
                  stake_pct=excluded.stake_pct,
                  cr_pct=excluded.cr_pct,
                  views=excluded.views,
                  clicks=excluded.clicks,
                  ctr=excluded.ctr,
                  cpc=excluded.cpc,
                  cpo=excluded.cpo,
                  cogs=excluded.cogs,
                  profit_net=excluded.profit_net,
                  roi_pct=excluded.roi_pct,
                  notes=excluded.notes,
                  updated_at=excluded.updated_at
                """.trimIndent(),
                arrayOf(
                  c.getString(iDate),
                  c.getString(iPeriodStart),
                  c.getString(iPeriodEnd),
                  c.getString(iBagId),
                  c.getString(iColor),
                  c.getString(iSource),
                  if (c.isNull(iHypothesis)) null else c.getString(iHypothesis),
                  if (c.isNull(iPrice)) null else c.getDouble(iPrice),
                  if (c.isNull(iOrders)) null else c.getDouble(iOrders),
                  if (c.isNull(iStock)) null else c.getDouble(iStock),
                  if (c.isNull(iRkSpend)) null else c.getDouble(iRkSpend),
                  if (c.isNull(iRkImp)) null else c.getDouble(iRkImp),
                  if (c.isNull(iRkClicks)) null else c.getDouble(iRkClicks),
                  if (c.isNull(iRkCtr)) null else c.getDouble(iRkCtr),
                  if (c.isNull(iRkCpc)) null else c.getDouble(iRkCpc),
                  if (c.isNull(iIgSpend)) null else c.getDouble(iIgSpend),
                  if (c.isNull(iIgImp)) null else c.getDouble(iIgImp),
                  if (c.isNull(iIgClicks)) null else c.getDouble(iIgClicks),
                  if (c.isNull(iIgCtr)) null else c.getDouble(iIgCtr),
                  if (c.isNull(iIgCpc)) null else c.getDouble(iIgCpc),
                  if (c.isNull(iStake)) null else c.getDouble(iStake),
                  if (c.isNull(iCr)) null else c.getDouble(iCr),
                  if (c.isNull(iViews)) null else c.getDouble(iViews),
                  if (c.isNull(iClicks)) null else c.getDouble(iClicks),
                  if (c.isNull(iCtr)) null else c.getDouble(iCtr),
                  if (c.isNull(iCpc)) null else c.getDouble(iCpc),
                  if (c.isNull(iCpo)) null else c.getDouble(iCpo),
                  if (c.isNull(iCogs)) null else c.getDouble(iCogs),
                  if (c.isNull(iProfit)) null else c.getDouble(iProfit),
                  if (c.isNull(iRoi)) null else c.getDouble(iRoi),
                  if (c.isNull(iNotes)) null else c.getString(iNotes),
                  if (c.isNull(iUpdatedAt)) null else c.getString(iUpdatedAt)
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

  private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { fis ->
      val buf = ByteArray(8192)
      while (true) {
        val r = fis.read(buf)
        if (r <= 0) break
        digest.update(buf, 0, r)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun sha256String(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private fun buildImagesIndex(imagesDir: File): JSONArray {
    val arr = JSONArray()
    if (!imagesDir.exists()) return arr

    imagesDir.walkTopDown()
      .filter { it.isFile }
      .sortedBy { it.relativeTo(imagesDir).invariantSeparatorsPath }
      .forEach { file ->
        val obj = JSONObject()
        obj.put("path", "images/" + file.relativeTo(imagesDir).invariantSeparatorsPath)
        obj.put("sha256", sha256File(file))
        obj.put("bytes", file.length())
        obj.put("mtime", file.lastModified())
        arr.put(obj)
      }

    return arr
  }

  private fun buildImagesCanonical(arr: JSONArray): String {
    val parts = ArrayList<String>()
    for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      parts.add(
        listOf(
          o.optString("path"),
          o.optString("sha256"),
          o.optLong("bytes").toString(),
          o.optLong("mtime").toString()
        ).joinToString("|")
      )
    }
    return parts.joinToString("\n")
  }

  private fun zipDirectory(sourceDir: File, zipFile: File) {
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      sourceDir.walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
          val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
          zos.putNextEntry(ZipEntry(relative))
          BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zos)
          }
          zos.closeEntry()
        }
    }
  }
}
