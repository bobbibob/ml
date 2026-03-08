#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

pack = Path("app/src/main/java/com/ml/app/data/PackDbSync.kt")
repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")

# ---------- PackDbSync.kt ----------
s = pack.read_text()

if 'private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"' not in s:
    s = s.replace(
        '  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"\n',
        '  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"\n'
        '  private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"\n',
        1
    )

old_ensure = '''    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_USER_COLOR_PRICE(
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        price REAL,
        PRIMARY KEY(bag_id, color)
      );
      """.trimIndent()
    )
'''
new_ensure = '''    db.execSQL(
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
'''
if old_ensure in s:
    s = s.replace(old_ensure, new_ensure, 1)

merge_anchor = '''        if (tableExists(fromDb, T_BAG_USER_COLOR_PRICE)) {
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
'''
merge_new = '''        if (tableExists(fromDb, T_BAG_USER_COLOR_PRICE)) {
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

        toDb.setTransactionSuccessful()
'''
if merge_anchor in s:
    s = s.replace(merge_anchor, merge_new, 1)

pack.write_text(s)

# ---------- SQLiteRepo.kt ----------
r = repo.read_text()

if 'data class BagStockOverrideRow(' not in r:
    insert = '''

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

'''
    last = r.rfind("\n}")
    if last == -1:
      raise SystemExit("SQLiteRepo class end not found")
    r = r[:last] + insert + "\n}"
    repo.write_text(r)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/PackDbSync.kt app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "add dated stock override foundation"
git push
