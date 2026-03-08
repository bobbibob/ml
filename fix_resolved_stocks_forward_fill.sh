#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

pattern = re.compile(
    r'''suspend fun getResolvedStocksForDate\(date: String\): List<StockResolvedRow> = withContext\(Dispatchers\.IO\) \{
    openDbReadWrite\(\)\.use \{ db ->
      val base = LinkedHashMap<Pair<String, String>, Double>\(\)

      db\.rawQuery\(
        """
        SELECT bag_id, color, stock
        FROM svodka
        WHERE date=\?
          AND bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
          AND color NOT IN \('__TOTAL__','TOTAL'\)
        ORDER BY bag_id, color
        """\.trimIndent\(\),
        arrayOf\(date\)
      \)\.use \{ c ->.*?return@withContext out
    \}
  \}''',
    re.DOTALL
)

replacement = '''suspend fun getResolvedStocksForDate(date: String): List<StockResolvedRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val base = LinkedHashMap<Pair<String, String>, Double>()

      db.rawQuery(
        """
        SELECT s1.bag_id, s1.color, s1.stock
        FROM svodka s1
        JOIN (
          SELECT bag_id, color, MAX(date) AS max_date
          FROM svodka
          WHERE date <= ?
            AND bag_id IS NOT NULL AND bag_id!=''
            AND color IS NOT NULL AND color!=''
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

      val overrides = getEffectiveStockOverrides(date)
      for (o in overrides) {
        base[o.bagId to o.color] = o.stock
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

p.write_text(new_s)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "forward fill resolved stocks by latest svodka date"
git push
