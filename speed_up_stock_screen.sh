#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
pack = Path("app/src/main/java/com/ml/app/data/PackDbSync.kt")
stock = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")

# ---------- PackDbSync.kt: indexes ----------
s = pack.read_text()

if 'idx_bag_stock_override_date_bag_color' not in s:
    anchor = '''    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_STOCK_OVERRIDE(
        effective_date TEXT NOT NULL,
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        stock REAL NOT NULL,
        PRIMARY KEY(effective_date, bag_id, color)
      );
      """.trimIndent()
    )
'''
    repl = '''    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $T_BAG_STOCK_OVERRIDE(
        effective_date TEXT NOT NULL,
        bag_id TEXT NOT NULL,
        color TEXT NOT NULL,
        stock REAL NOT NULL,
        PRIMARY KEY(effective_date, bag_id, color)
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_date_bag_color ON $T_BAG_STOCK_OVERRIDE(effective_date, bag_id, color)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_stock_override_bag_color_date ON $T_BAG_STOCK_OVERRIDE(bag_id, color, effective_date)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_svodka_date_bag_color ON svodka(date, bag_id, color)")
'''
    if anchor in s:
        s = s.replace(anchor, repl, 1)

pack.write_text(s)

# ---------- SQLiteRepo.kt: light bag list ----------
r = repo.read_text()

if "suspend fun listStockBagMeta()" not in r:
    insert = '''

  data class StockBagMeta(
    val bagId: String,
    val bagName: String,
    val photoPath: String?
  )

  suspend fun listStockBagMeta(): List<StockBagMeta> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val out = ArrayList<StockBagMeta>()
      db.rawQuery(
        """
        SELECT
          s.bag_id AS bag_id,
          COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id) AS bag_name,
          COALESCE(NULLIF(u.photo_path,''), MAX(m.image_path)) AS photo_path
        FROM svodka s
        LEFT JOIN bags b ON b.bag_id = s.bag_id
        LEFT JOIN bag_user u ON u.bag_id = s.bag_id
        LEFT JOIN media m
          ON m.entity_type='bag'
         AND m.entity_key=s.bag_id
        WHERE s.bag_id IS NOT NULL AND s.bag_id!=''
        GROUP BY s.bag_id, COALESCE(NULLIF(u.name,''), b.bag_name, s.bag_id), COALESCE(NULLIF(u.photo_path,''), MAX(m.image_path))
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
  }

'''
    last = r.rfind("\n}")
    if last == -1:
        raise SystemExit("SQLiteRepo end not found")
    r = r[:last] + insert + "\n}"

repo.write_text(r)

# ---------- StockScreen.kt: stop using loadTimeline(180) ----------
u = stock.read_text()

old = '''        val meta = repo.loadTimeline(180)
            .flatMap { it.byBags }
            .distinctBy { it.bagId }
            .sortedBy { it.bagName.lowercase() }

        val stocks = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }

        items = meta.mapNotNull { bag ->
            val rows = stocks[bag.bagId]
                ?.sortedBy { it.color.lowercase() }
                ?.map { it.color to it.stock }

            if (rows.isNullOrEmpty()) null
            else StockBagUi(
                bagId = bag.bagId,
                bagName = bag.bagName,
                photoPath = bag.imagePath,
                colors = rows
            )
        }
'''

new = '''        val meta = repo.listStockBagMeta()

        val stocks = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }

        items = meta.mapNotNull { bag ->
            val rows = stocks[bag.bagId]
                ?.sortedBy { it.color.lowercase() }
                ?.map { it.color to it.stock }

            if (rows.isNullOrEmpty()) null
            else StockBagUi(
                bagId = bag.bagId,
                bagName = bag.bagName,
                photoPath = bag.photoPath,
                colors = rows
            )
        }
'''

if old not in u:
    raise SystemExit("StockScreen reload block not found")

u = u.replace(old, new, 1)
stock.write_text(u)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/PackDbSync.kt \
        app/src/main/java/com/ml/app/data/SQLiteRepo.kt \
        app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "speed up stock screen with indexes and light bag meta" || true
git push
