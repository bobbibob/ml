#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")

s = repo.read_text()

if "data class BagEditorSeed(" not in s:
    block = r'''

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
        SELECT MAX(date) AS d
        FROM svodka
        WHERE bag_id=? AND date IS NOT NULL AND date!=''
        """.trimIndent(),
        arrayOf(bagId)
      ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else c.getString(0)
      } ?: return@withContext null

      val head = db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          COALESCE(NULLIF(u.hypothesis,''), MAX(s.hypothesis)) AS hypothesis,
          COALESCE(u.price, MAX(s.price)) AS price,
          COALESCE(u.cogs, MAX(s.cogs)) AS cogs
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        WHERE s.bag_id=? AND s.date=? AND s.color IN ('__TOTAL__','TOTAL')
        GROUP BY s.bag_id, COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id), COALESCE(NULLIF(u.hypothesis,''), MAX(s.hypothesis)), COALESCE(u.price, MAX(s.price)), COALESCE(u.cogs, MAX(s.cogs))
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        if (!c.moveToFirst()) return@withContext null
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iHyp = c.getColumnIndexOrThrow("hypothesis")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iCogs = c.getColumnIndexOrThrow("cogs")
        arrayOf(
          c.getString(iBagId),
          c.getString(iBagName),
          if (c.isNull(iHyp)) null else c.getString(iHyp),
          if (c.isNull(iPrice)) null else c.getDouble(iPrice),
          if (c.isNull(iCogs)) null else c.getDouble(iCogs)
        )
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

      BagEditorSeed(
        bagId = head[0] as String,
        bagName = head[1] as String,
        hypothesis = head[2] as String?,
        price = head[3] as Double?,
        cogs = head[4] as Double?,
        colors = colors
      )
    }
  }
'''
    idx = s.rfind("}")
    s = s[:idx] + block + "\n" + s[idx:]
    repo.write_text(s)

u = ui.read_text()

pattern = re.compile(r'''LaunchedEffect\(selectedBagId\)\s*\{\n.*?\n    \}''', re.S)
replacement = '''LaunchedEffect(selectedBagId) {
        val id = selectedBagId
        if (id.isNullOrBlank()) return@LaunchedEffect

        val row = repo.getBagUser(id)
        val rowColors = repo.getBagUserColors(id)
        val seed = repo.getBagEditorSeed(id)

        resetForm()

        name = row?.name ?: seed?.bagName.orEmpty()
        hypothesis = row?.hypothesis ?: seed?.hypothesis.orEmpty()
        priceAll = row?.price?.toString() ?: seed?.price?.toString().orEmpty()
        cost = row?.cogs?.toString() ?: seed?.cogs?.toString().orEmpty()
        cardType = row?.cardType ?: "classic"
        photoPath = row?.photoPath

        colors.clear()
        if (rowColors.isNotEmpty()) {
            colors.addAll(rowColors.distinct())
        } else {
            colors.addAll(seed?.colors.orEmpty().distinct())
        }
        colorPrices.clear()
    }'''
u2, n = pattern.subn(replacement, u, count=1)
if n != 1:
    raise SystemExit("Не удалось заменить LaunchedEffect(selectedBagId)")
ui.write_text(u2)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "fill editor from svodka fallback" || true
git push
