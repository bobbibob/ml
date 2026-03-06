#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

pattern = re.compile(r'''
\s*suspend\ fun\ listBagPickerRows\(\):\ List<BagPickerRow>\ =\ withContext\(Dispatchers\.IO\)\ \{
.*?
\s*\}
''', re.S | re.X)

replacement = '''
  suspend fun listBagPickerRows(): List<BagPickerRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val images = queryImagesByBagId(db)
      val out = ArrayList<BagPickerRow>()
      db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          u.photo_path AS photo_path
        FROM (
          SELECT DISTINCT bag_id
          FROM svodka
          WHERE bag_id IS NOT NULL AND bag_id != ''
        ) s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        ORDER BY bag_name COLLATE NOCASE
        """.trimIndent(),
        null
      ).use { c ->
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iPhoto = c.getColumnIndexOrThrow("photo_path")

        while (c.moveToNext()) {
          val bagId = c.getString(iBagId)
          val dbPhoto = if (c.isNull(iPhoto)) null else c.getString(iPhoto)
          out.add(
            BagPickerRow(
              bagId = bagId,
              bagName = c.getString(iBagName),
              photoPath = dbPhoto ?: images[bagId]
            )
          )
        }
      }
      out
    }
  }
'''

s2, n = pattern.subn('\n' + replacement + '\n', s, count=1)
if n != 1:
    raise SystemExit("Не удалось заменить listBagPickerRows")
p.write_text(s2)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "show picker photos from db and pack images" || true
git push
