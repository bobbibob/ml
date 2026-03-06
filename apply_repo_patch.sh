#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/data/SQLiteRepo.kt"

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

block = '''
  data class BagPickerRow(
    val bagId: String,
    val bagName: String,
    val photoPath: String?
  )

  suspend fun listBagPickerRows(): List<BagPickerRow> = withContext(Dispatchers.IO) {
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
  }

  suspend fun deleteBagUsers(bagIds: List<String>) = withContext(Dispatchers.IO) {
    if (bagIds.isEmpty()) return@withContext

    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        for (id in bagIds.distinct()) {
          db.execSQL("DELETE FROM bag_user_colors WHERE bag_id=?", arrayOf(id))
          db.execSQL("DELETE FROM bag_user WHERE bag_id=?", arrayOf(id))
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }
'''.strip("\n")

if "data class BagPickerRow(" not in s:
    idx = s.rfind("}")
    if idx == -1:
        raise SystemExit("Не найдена закрывающая } в SQLiteRepo.kt")
    s = s[:idx] + "\n\n" + block + "\n\n" + s[idx:]
    p.write_text(s)
    print("patched")
else:
    print("already patched")
PY

git add "$FILE"
git commit -m "add bag picker and delete methods" || true
git push
