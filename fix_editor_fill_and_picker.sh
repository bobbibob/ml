#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")

u = ui.read_text()
r = repo.read_text()

# ---------- UI ----------
u = u.replace(
    "import com.ml.app.domain.BagOrdersSummary",
    "import com.ml.app.data.SQLiteRepo.BagPickerRow"
)

u = u.replace(
    "var bagItems by remember { mutableStateOf<List<BagOrdersSummary>>(emptyList()) }",
    "var bagItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }"
)

u = u.replace(
    '        cost = ""\n        priceAll = ""',
    '        cost = ""\n        photoPath = null\n        priceAll = ""'
)

u = re.sub(
    r'''LaunchedEffect\(tab\)\s*\{\s*if \(tab == 1\) \{\s*bagItems = repo\.loadTimeline\(180\)\s*\.flatMap \{ day -> day\.byBags \}\s*\.distinctBy \{ bag -> bag\.bagId \}\s*\.sortedBy \{ bag -> bag\.bagName\.lowercase\(\) \}\s*\}\s*\}''',
    '''LaunchedEffect(tab) {
        if (tab == 1) {
            bagItems = repo.listBagPickerRows()
        }
    }''',
    u,
    flags=re.S
)

u = u.replace(
    '''                                onClick = {
                                    selectedBagId = bag.bagId
                                    name = bag.bagName
                                    tab = 0
                                }''',
    '''                                onClick = {
                                    selectedBagId = bag.bagId
                                    tab = 0
                                }'''
)

u = re.sub(
    r'''LaunchedEffect\(selectedBagId\)\s*\{.*?colorPrices\.clear\(\)\s*\}''',
    '''LaunchedEffect(selectedBagId) {
        val id = selectedBagId
        if (id.isNullOrBlank()) return@LaunchedEffect

        val row = repo.getBagUser(id)
        val rowColors = repo.getBagUserColors(id)
        val seed = repo.getBagEditorSeed(id)

        resetForm()

        name = row?.name ?: seed?.bagName ?: bagItems.firstOrNull { it.bagId == id }?.bagName.orEmpty()
        hypothesis = row?.hypothesis ?: seed?.hypothesis.orEmpty()
        priceAll = row?.price?.toString() ?: seed?.price?.toString().orEmpty()
        cost = row?.cogs?.toString() ?: seed?.cogs?.toString().orEmpty()
        cardType = row?.cardType ?: "classic"
        photoPath = row?.photoPath ?: bagItems.firstOrNull { it.bagId == id }?.photoPath

        colors.clear()
        if (rowColors.isNotEmpty()) {
            colors.addAll(rowColors.distinct())
        } else {
            colors.addAll(seed?.colors.orEmpty().distinct())
        }
        colorPrices.clear()
    }''',
    u,
    flags=re.S
)

ui.write_text(u)

# ---------- REPO ----------
if "data class BagEditorSeed(" not in r:
    block = '''

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

      var outBagName: String? = null
      var outHypothesis: String? = null
      var outPrice: Double? = null
      var outCogs: Double? = null

      db.rawQuery(
        """
        SELECT
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          COALESCE(NULLIF(u.hypothesis,''), MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.hypothesis END)) AS hypothesis,
          COALESCE(u.price, MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.price END)) AS price,
          COALESCE(u.cogs, MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.cogs END)) AS cogs
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        WHERE s.bag_id=? AND s.date=?
        GROUP BY COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id), COALESCE(NULLIF(u.hypothesis,''), MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.hypothesis END)), COALESCE(u.price, MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.price END)), COALESCE(u.cogs, MAX(CASE WHEN s.color IN ('__TOTAL__','TOTAL') THEN s.cogs END))
        """.trimIndent(),
        arrayOf(bagId, latestDate)
      ).use { c ->
        if (c.moveToFirst()) {
          val iBagName = c.getColumnIndexOrThrow("bag_name")
          val iHyp = c.getColumnIndexOrThrow("hypothesis")
          val iPrice = c.getColumnIndexOrThrow("price")
          val iCogs = c.getColumnIndexOrThrow("cogs")
          outBagName = c.getString(iBagName)
          outHypothesis = if (c.isNull(iHyp)) null else c.getString(iHyp)
          outPrice = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          outCogs = if (c.isNull(iCogs)) null else c.getDouble(iCogs)
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

      val bagName = outBagName ?: return@withContext null
      BagEditorSeed(
        bagId = bagId,
        bagName = bagName,
        hypothesis = outHypothesis,
        price = outPrice,
        cogs = outCogs,
        colors = colors
      )
    }
  }
'''
    idx = r.rfind("}")
    r = r[:idx] + block + "\n" + r[idx:]

repo.write_text(r)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "fix editor picker and svodka fallback" || true
git push
