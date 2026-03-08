#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
stock = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")

# ---------- SQLiteRepo.kt ----------
s = repo.read_text()

pattern = re.compile(
    r'''suspend fun getResolvedStocksForDate\(date: String\): List<StockResolvedRow> = withContext\(Dispatchers\.IO\) \{
    openDbReadWrite\(\)\.use \{ db ->
.*?
    \}
  \}''',
    re.DOTALL
)

replacement = '''suspend fun getResolvedStocksForDate(date: String): List<StockResolvedRow> = withContext(Dispatchers.IO) {
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
  }'''

new_s, n = pattern.subn(replacement, s, count=1)
if n == 0:
    raise SystemExit("getResolvedStocksForDate block not found")

repo.write_text(new_s)

# ---------- StockScreen.kt ----------
u = stock.read_text()

u = u.replace(
    '        Text(\n            text = "Остатки на $date",\n            style = MaterialTheme.typography.headlineSmall\n        )',
    '        Text(\n            text = "Остатки",\n            style = MaterialTheme.typography.headlineSmall\n        )'
)

stock.write_text(u)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt \
        app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "speed up stocks query and hide date title" || true
git push
