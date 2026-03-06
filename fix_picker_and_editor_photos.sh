#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

old = '''  suspend fun listBagPickerRows(): List<BagPickerRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
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
          out.add(
            BagPickerRow(
              bagId = c.getString(iBagId),
              bagName = c.getString(iBagName),
              photoPath = if (c.isNull(iPhoto)) null else c.getString(iPhoto)
            )
          )
        }
      }
      out
    }
  }'''

new = '''  suspend fun listBagPickerRows(): List<BagPickerRow> = withContext(Dispatchers.IO) {
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
  }'''

if old not in s:
    raise SystemExit("function block not found")

s = s.replace(old, new, 1)
p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "fallback picker photos to pack images" || true
git push
