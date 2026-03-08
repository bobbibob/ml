#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

pattern = re.compile(
    r'''suspend fun listStockBagMeta\(\): List<StockBagMeta> = withContext\(Dispatchers\.IO\) \{
    openDbReadWrite\(\)\.use \{ db ->
      val out = ArrayList<StockBagMeta>\(\)
      db\.rawQuery\(
        """
        SELECT
          s\.bag_id AS bag_id,
          COALESCE\(NULLIF\(u\.name,''\), b\.bag_name, s\.bag_id\) AS bag_name,
          COALESCE\(NULLIF\(u\.photo_path,''\), MAX\(m\.image_path\)\) AS photo_path
        FROM svodka s
        LEFT JOIN bags b ON b\.bag_id = s\.bag_id
        LEFT JOIN bag_user u ON u\.bag_id = s\.bag_id
        LEFT JOIN media m
          ON m\.entity_type='bag'
         AND m\.entity_key=s\.bag_id
        WHERE s\.bag_id IS NOT NULL AND s\.bag_id!=''
        GROUP BY s\.bag_id, COALESCE\(NULLIF\(u\.name,''\), b\.bag_name, s\.bag_id\), COALESCE\(NULLIF\(u\.photo_path,''\), MAX\(m\.image_path\)\)
        ORDER BY bag_name COLLATE NOCASE
        """\.trimIndent\(\),
        null
      \)\.use \{ c ->.*?out
    \}
  \}''',
    re.DOTALL
)

replacement = '''suspend fun listStockBagMeta(): List<StockBagMeta> = withContext(Dispatchers.IO) {
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
  }'''

new_s, n = pattern.subn(replacement, s, count=1)
if n == 0:
    raise SystemExit("listStockBagMeta block not found")

p.write_text(new_s)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "fix stock screen crash in bag meta query" || true
git push
