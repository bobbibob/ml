#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/data/PackUploadManager.kt")
s = p.read_text()

# 1) const
if 'private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"' not in s:
    s = s.replace(
        '  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"\n',
        '  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"\n'
        '  private const val T_BAG_STOCK_OVERRIDE = "bag_stock_override"\n',
        1
    )

# 2) ensureUserTables
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
  }
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
  }
'''
if old_ensure in s:
    s = s.replace(old_ensure, new_ensure, 1)

# 3) merge block
anchor = '''        if (tableExists(fromDb, T_BAG_USER_COLOR_PRICE)) {
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
if anchor in s:
    s = s.replace(anchor, merge_new, 1)

p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/PackUploadManager.kt
git commit -m "include stock overrides in pack upload"
git push
