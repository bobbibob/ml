#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

repo = Path("app/src/main/java/com/ml/app/data/PackDbSync.kt")
sqlite = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")

r = repo.read_text()
s = sqlite.read_text()
u = ui.read_text()

# ---------- PackDbSync.kt ----------
if 'private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"' not in r:
    r = r.replace(
        '  private const val T_BAG_USER_COLORS = "bag_user_colors"\n',
        '  private const val T_BAG_USER_COLORS = "bag_user_colors"\n'
        '  private const val T_BAG_USER_COLOR_PRICE = "bag_user_color_price"\n',
        1
    )

ensure_old = '''    // 3) bag user colors
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
'''
ensure_new = '''    // 3) bag user colors
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
'''
if ensure_old in r:
    r = r.replace(ensure_old, ensure_new, 1)

merge_marker = '''        // merge bag_user_colors
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

        toDb.setTransactionSuccessful()
'''
merge_new = '''        // merge bag_user_colors
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
'''
if merge_marker in r:
    r = r.replace(merge_marker, merge_new, 1)

repo.write_text(r)

# ---------- SQLiteRepo.kt ----------
if 'data class BagColorPriceRow(' not in s:
    insert_marker = '  suspend fun getBagUserColors(bagId: String): List<String> = withContext(Dispatchers.IO) {'
    block = '''
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

'''
    if insert_marker in s:
        s = s.replace(insert_marker, block + insert_marker, 1)

sqlite.write_text(s)

# ---------- AddEditArticleScreen.kt ----------
if 'import com.ml.app.data.SQLiteRepo.BagColorPriceRow' not in u:
    u = u.replace(
        'import com.ml.app.data.SQLiteRepo.BagPickerRow\n',
        'import com.ml.app.data.SQLiteRepo.BagPickerRow\n'
        'import com.ml.app.data.SQLiteRepo.BagColorPriceRow\n',
        1
    )

old_load = '''    LaunchedEffect(selectedBagId) {
        val id = selectedBagId ?: return@LaunchedEffect
        loadBagFromPicker(id)
    }
'''
new_load = '''    LaunchedEffect(selectedBagId) {
        val id = selectedBagId ?: return@LaunchedEffect
        loadBagFromPicker(id)

        val savedPrices = kotlin.runCatching { repo.getBagColorPrices(id) }.getOrDefault(emptyList())
        if (savedPrices.isNotEmpty()) {
            for (i in colorDrafts.indices) {
                val item = colorDrafts[i]
                val saved = savedPrices.firstOrNull { it.color == item.color }?.price
                if (saved != null) {
                    colorDrafts[i] = item.copy(priceText = saved.toString())
                }
            }
        }
    }
'''
if old_load in u:
    u = u.replace(old_load, new_load, 1)

old_save = '''                Button(
                    onClick = { showExitDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
                }
'''
new_save = '''                Button(
                    onClick = {
                        val id = selectedBagId
                        if (!id.isNullOrBlank()) {
                            kotlin.runCatching {
                                repo.replaceBagColorPrices(
                                    id,
                                    colorDrafts.map {
                                        BagColorPriceRow(
                                            color = it.color,
                                            price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                        )
                                    }
                                )
                            }
                        }
                        showExitDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
                }
'''
if old_save in u:
    u = u.replace(old_save, new_save, 1)

ui.write_text(u)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/PackDbSync.kt app/src/main/java/com/ml/app/data/SQLiteRepo.kt app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "add safe color price persistence"
git push
