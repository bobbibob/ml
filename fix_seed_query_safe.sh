#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

pattern = re.compile(r'''
\s*data\ class\ BagEditorSeed\(
.*?
\s*suspend\ fun\ getBagEditorSeed\(bagId:\ String\):\ BagEditorSeed\?\ =\ withContext\(Dispatchers\.IO\)\ \{
.*?
\s*\}
''', re.S | re.X)

replacement = '''
  data class BagEditorSeed(
    val bagId: String,
    val bagName: String,
    val hypothesis: String?,
    val price: Double?,
    val cogs: Double?,
    val colors: List<String>
  )

  suspend fun getBagEditorSeed(bagId: String): BagEditorSeed? = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val latestDate = db.rawQuery(
        """
        SELECT MAX(date)
        FROM svodka
        WHERE bag_id=? AND date IS NOT NULL AND date!=''
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0)
      } ?: return@withContext null

      var bagName: String? = null
      var hypothesis: String? = null
      var price: Double? = null
      var cogs: Double? = null

      db.rawQuery(
        """
        SELECT
          COALESCE(b.bag_name, s.bag_id) AS bag_name,
          s.hypothesis AS hypothesis,
          s.price AS price,
          s.cogs AS cogs
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        WHERE s.bag_id=? AND s.date=? AND s.color IN ('__TOTAL__','TOTAL')
        LIMIT 1
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        if (c.moveToFirst()) {
          val iBagName = c.getColumnIndexOrThrow("bag_name")
          val iHyp = c.getColumnIndexOrThrow("hypothesis")
          val iPrice = c.getColumnIndexOrThrow("price")
          val iCogs = c.getColumnIndexOrThrow("cogs")
          bagName = c.getString(iBagName)
          hypothesis = if (c.isNull(iHyp)) null else c.getString(iHyp)
          price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          cogs = if (c.isNull(iCogs)) null else c.getDouble(iCogs)
        }
      }

      if (bagName == null) {
        db.rawQuery(
          """
          SELECT COALESCE(b.bag_name, s.bag_id) AS bag_name
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.bag_id=?
          LIMIT 1
          """.trimIndent(),
          arrayOf(bagId)
        ).use { c ->
          if (c.moveToFirst()) bagName = c.getString(0)
        }
      }

      val colors = ArrayList<String>()
      db.rawQuery(
        """
        SELECT DISTINCT color
        FROM svodka
        WHERE bag_id=? AND date=? AND color IS NOT NULL AND color!='' AND color NOT IN ('__TOTAL__','TOTAL')
        ORDER BY color COLLATE NOCASE
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        while (c.moveToNext()) colors.add(c.getString(0))
      }

      val finalBagName = bagName ?: return@withContext null
      BagEditorSeed(
        bagId = bagId,
        bagName = finalBagName,
        hypothesis = hypothesis,
        price = price,
        cogs = cogs,
        colors = colors
      )
    }
  }
'''

s2, n = pattern.subn('\n' + replacement + '\n', s, count=1)
if n != 1:
    raise SystemExit("Не удалось заменить getBagEditorSeed")
p.write_text(s2)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "fix safe editor seed query" || true
git push
