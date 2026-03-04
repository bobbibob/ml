#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Run this from the repo root (~/ml)
REPO_DIR="${1:-$PWD}"
cd "$REPO_DIR"

echo "== Repo: $PWD =="

# -----------------------------
# 1) Ensure Gradle Wrapper exists (gradlew + wrapper jar)
# -----------------------------
if [ ! -f "./gradlew" ] || [ ! -f "./gradle/wrapper/gradle-wrapper.jar" ] || [ ! -f "./gradle/wrapper/gradle-wrapper.properties" ]; then
  echo "== Gradle wrapper missing -> adding wrapper (Gradle 9.3.1) =="
  mkdir -p gradle/wrapper

  # Download wrapper scripts + jar from Gradle repo (raw)
  # (No need to install gradle in Termux)
  command -v curl >/dev/null 2>&1 || { echo "curl not found. Install: pkg install -y curl"; exit 1; }

  curl -L -o gradlew https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradlew
  curl -L -o gradlew.bat https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradlew.bat
  curl -L -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradle/wrapper/gradle-wrapper.jar

  cat > gradle/wrapper/gradle-wrapper.properties <<'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

  chmod +x gradlew
else
  echo "== Gradle wrapper already present =="
  chmod +x gradlew || true
fi

# -----------------------------
# 2) Fix SQLiteRepo.kt to match your DB schema:
#    bags(bag_id, bag_name), svodka has rk_* and ig_* columns
#    and media.entity_type must be 'bag'
# -----------------------------
echo "== Writing SQLiteRepo.kt (bags join fix + rk/ig metrics + entity_type='bag') =="
cat > app/src/main/java/com/ml/app/data/SQLiteRepo.kt <<'EOF'
package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class SQLiteRepo(private val context: Context) {

  private fun openDbReadOnly(): SQLiteDatabase {
    val dbFile: File = PackPaths.dbFile(context)
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
  }

  private fun packFile(rel: String?): String? {
    if (rel.isNullOrBlank()) return null
    val f = File(PackPaths.packDir(context), rel)
    return if (f.exists()) f.absolutePath else null
  }

  suspend fun loadTimeline(limitDays: Int = 180): List<DaySummary> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)
      val days = ArrayList<DaySummary>()

      db.rawQuery(
        """
          SELECT s.date AS date,
                 s.bag_id AS bag_id,
                 COALESCE(b.bag_name, s.bag_id) AS bag_name,
                 SUM(COALESCE(s.orders,0)) AS orders
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.date IS NOT NULL AND s.date != ''
            AND s.bag_id IS NOT NULL AND s.bag_id != ''
          GROUP BY s.date, s.bag_id
          ORDER BY s.date DESC, orders DESC
        """.trimIndent(),
        null
      ).use { c ->
        val iDate = c.getColumnIndexOrThrow("date")
        val iBagId = c.getColumnIndexOrThrow("bag_id")
        val iBagName = c.getColumnIndexOrThrow("bag_name")
        val iOrders = c.getColumnIndexOrThrow("orders")

        var curDate: String? = null
        var curTotal = 0
        var curList = ArrayList<BagOrdersSummary>()

        fun flush() {
          val d = curDate ?: return
          days.add(DaySummary(date = d, totalOrders = curTotal, byBags = curList))
        }

        while (c.moveToNext()) {
          val date = c.getString(iDate)
          if (curDate != null && date != curDate) {
            flush()
            curTotal = 0
            curList = ArrayList()
          }
          curDate = date

          val bagId = c.getString(iBagId)
          val bagName = c.getString(iBagName)
          val orders = c.getDouble(iOrders).roundToInt()
          curTotal += orders

          curList.add(
            BagOrdersSummary(
              bagId = bagId,
              bagName = bagName,
              orders = orders,
              imagePath = images[bagId]
            )
          )
        }
        if (curDate != null) flush()
      }

      days.take(limitDays)
    }
  }

  suspend fun loadForDate(date: String): List<BagDayRow> = withContext(Dispatchers.IO) {
    openDbReadOnly().use { db ->
      val images = queryImagesByBagId(db)
      val out = ArrayList<BagDayRow>()

      db.rawQuery(
        """
          SELECT s.bag_id AS bag_id,
                 COALESCE(b.bag_name, s.bag_id) AS bag_name,
                 MAX(s.price) AS price,
                 MAX(s.hypothesis) AS hypothesis,
                 SUM(COALESCE(s.orders,0)) AS orders,

                 SUM(COALESCE(s.rk_spend,0)) AS rk_spend,
                 SUM(COALESCE(s.rk_impressions,0)) AS rk_impressions,
                 SUM(COALESCE(s.rk_clicks,0)) AS rk_clicks,

                 SUM(COALESCE(s.ig_spend,0)) AS ig_spend,
                 SUM(COALESCE(s.ig_impressions,0)) AS ig_impressions,
                 SUM(COALESCE(s.ig_clicks,0)) AS ig_clicks
          FROM svodka s
          LEFT JOIN bags b ON b.bag_id = s.bag_id
          WHERE s.date=? AND s.bag_id IS NOT NULL AND s.bag_id != ''
          GROUP BY s.bag_id
          ORDER BY orders DESC
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iName = c.getColumnIndexOrThrow("bag_name")
        val iPrice = c.getColumnIndexOrThrow("price")
        val iHyp = c.getColumnIndexOrThrow("hypothesis")
        val iOrders = c.getColumnIndexOrThrow("orders")

        val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
        val iRkImpr = c.getColumnIndexOrThrow("rk_impressions")
        val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")

        val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
        val iIgImpr = c.getColumnIndexOrThrow("ig_impressions")
        val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iId)
          val bagName = c.getString(iName)

          val price = if (c.isNull(iPrice)) null else c.getDouble(iPrice)
          val hyp = if (c.isNull(iHyp)) null else c.getString(iHyp)

          val totalOrders = c.getDouble(iOrders)

          val rkSpend = c.getDouble(iRkSpend)
          val rkImpr = c.getLong(iRkImpr)
          val rkClicks = c.getLong(iRkClicks)

          val igSpend = c.getDouble(iIgSpend)
          val igImpr = c.getLong(iIgImpr)
          val igClicks = c.getLong(iIgClicks)

          val rkCtr = if (rkImpr > 0) rkClicks.toDouble() / rkImpr.toDouble() else 0.0
          val rkCpc = if (rkClicks > 0) rkSpend / rkClicks.toDouble() else 0.0

          val igCtr = if (igImpr > 0) igClicks.toDouble() / igImpr.toDouble() else 0.0
          val igCpc = if (igClicks > 0) igSpend / igClicks.toDouble() else 0.0

          val rk = AdsMetrics(spend = rkSpend, impressions = rkImpr, clicks = rkClicks, ctr = rkCtr, cpc = rkCpc)
          val ig = AdsMetrics(spend = igSpend, impressions = igImpr, clicks = igClicks, ctr = igCtr, cpc = igCpc)

          val totalSpend = rkSpend + igSpend
          val totalImpr = rkImpr + igImpr
          val totalClicks = rkClicks + igClicks
          val totalCtr = if (totalImpr > 0) totalClicks.toDouble() / totalImpr.toDouble() else 0.0
          val totalCpc = if (totalClicks > 0) totalSpend / totalClicks.toDouble() else 0.0
          val totalAds = AdsMetrics(spend = totalSpend, impressions = totalImpr, clicks = totalClicks, ctr = totalCtr, cpc = totalCpc)

          val cpo = if (totalOrders > 0) totalSpend / totalOrders else 0.0

          out.add(
            BagDayRow(
              bagId = bagId,
              bagName = bagName,
              price = price,
              hypothesis = hyp,
              imagePath = images[bagId],
              totalOrders = totalOrders,
              totalSpend = totalSpend,
              cpo = cpo,
              ordersByColors = queryOrdersByColors(db, date, bagId),
              stockByColors = queryStockByColors(db, date, bagId),
              rk = rk,
              ig = ig,
              totalAds = totalAds
            )
          )
        }
      }

      out
    }
  }

  fun queryImagesByBagId(db: SQLiteDatabase): Map<String, String?> {
    val out = HashMap<String, String?>()
    db.rawQuery(
      """
      SELECT entity_key, COALESCE(thumbnail_path, image_path) AS p
      FROM media
      WHERE entity_type='bag'
      """.trimIndent(),
      null
    ).use { c ->
      val iKey = c.getColumnIndexOrThrow("entity_key")
      val iP = c.getColumnIndexOrThrow("p")
      while (c.moveToNext()) {
        val key = c.getString(iKey)
        val rel = c.getString(iP)
        out[key] = packFile(rel)
      }
    }
    return out
  }

  fun queryOrdersByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val out = ArrayList<ColorValue>()
    db.rawQuery(
      """
        SELECT color, SUM(COALESCE(orders,0)) AS v
        FROM svodka
        WHERE date=? AND bag_id=? AND color IS NOT NULL AND color != '' AND color NOT IN ('__TOTAL__','TOTAL')
        GROUP BY color
        ORDER BY v DESC
      """.trimIndent(),
      arrayOf(date, bagId)
    ).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) out.add(ColorValue(c.getString(ic), c.getDouble(iv)))
    }
    return out
  }

  fun queryStockByColors(db: SQLiteDatabase, date: String, bagId: String): List<ColorValue> {
    val out = ArrayList<ColorValue>()
    db.rawQuery(
      """
        SELECT color, SUM(COALESCE(stock,0)) AS v
        FROM svodka
        WHERE date=? AND bag_id=? AND color IS NOT NULL AND color != '' AND color NOT IN ('__TOTAL__','TOTAL')
        GROUP BY color
        ORDER BY v DESC
      """.trimIndent(),
      arrayOf(date, bagId)
    ).use { c ->
      val ic = c.getColumnIndexOrThrow("color")
      val iv = c.getColumnIndexOrThrow("v")
      while (c.moveToNext()) out.add(ColorValue(c.getString(ic), c.getDouble(iv)))
    }
    return out
  }

  suspend fun fmtMoney(v: Double): String = withContext(Dispatchers.Default) {
    val r = (v * 100.0).roundToInt() / 100.0
    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
  }
}
EOF

# -----------------------------
# 3) Ensure a real (non-empty) test DB exists in testdata/data.sqlite
#    We auto-find the biggest data.sqlite under ~/storage/downloads and common paths.
# -----------------------------
echo "== Ensuring testdata/data.sqlite is non-empty and has svodka =="

mkdir -p testdata

# Candidate roots (Termux common)
CANDIDATE_ROOTS=(
  "$PWD"
  "$HOME"
  "$HOME/storage/downloads"
  "$HOME/storage/shared/Download"
  "$HOME/downloads"
  "$HOME/Downloads"
)

# Find all data.sqlite candidates, pick the largest (most likely real DB)
BEST_DB=""
BEST_SIZE=0

for root in "${CANDIDATE_ROOTS[@]}"; do
  [ -d "$root" ] || continue
  while IFS= read -r f; do
    sz="$(wc -c < "$f" 2>/dev/null || echo 0)"
    if [ "$sz" -gt "$BEST_SIZE" ]; then
      BEST_SIZE="$sz"
      BEST_DB="$f"
    fi
  done < <(find "$root" -maxdepth 6 -type f -name "data.sqlite" 2>/dev/null || true)
done

if [ -z "$BEST_DB" ] || [ "$BEST_SIZE" -le 1024 ]; then
  echo "ERROR: Couldn't find a non-empty data.sqlite automatically."
  echo "Put your real DB at: testdata/data.sqlite (must contain table svodka), then rerun."
  exit 1
fi

echo "Using DB: $BEST_DB  (size=$BEST_SIZE bytes)"
SRC="$(readlink -f "$BEST_DB")"; DST="$(readlink -f testdata/data.sqlite)"; if [ "$SRC" != "$DST" ]; then cp -f "$BEST_DB" testdata/data.sqlite; else echo "DB already in place"; fi

# Verify sqlite3 exists
command -v sqlite3 >/dev/null 2>&1 || { echo "sqlite3 not found. Install: pkg install -y sqlite"; exit 1; }

echo "Tables in testdata/data.sqlite:"
sqlite3 testdata/data.sqlite ".tables" || true

# Hard check for svodka table
if ! sqlite3 testdata/data.sqlite "SELECT 1 FROM sqlite_master WHERE type='table' AND name='svodka' LIMIT 1;" | grep -q 1; then
  echo "ERROR: testdata/data.sqlite does NOT contain svodka table."
  echo "It copied: $BEST_DB but that file isn't the right DB."
  exit 1
fi

echo "svodka rows:"
sqlite3 testdata/data.sqlite "SELECT COUNT(*) FROM svodka;" || true

# -----------------------------
# 4) Write a robust GitHub Actions workflow:
#    - chmod +x gradlew
#    - build APK
#    - install sqlite3
#    - run SQL checks with correct bags schema
#    - upload APK artifact
# -----------------------------
echo "== Writing .github/workflows/android.yml =="
mkdir -p .github/workflows
cat > .github/workflows/android.yml <<'EOF'
name: Android CI

on:
  push:
    branches: [ "main" ]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Build Debug APK
        run: ./gradlew :app:assembleDebug

      - name: Install sqlite3
        run: sudo apt-get update && sudo apt-get install -y sqlite3

      - name: CI SQL checks on test database
        run: |
          set -e
          test -f testdata/data.sqlite
          ls -la testdata/data.sqlite

          echo "Tables:"
          sqlite3 testdata/data.sqlite ".tables"

          echo "svodka rows:"
          sqlite3 testdata/data.sqlite "SELECT COUNT(*) FROM svodka;"

          echo "bags schema:"
          sqlite3 testdata/data.sqlite "PRAGMA table_info(bags);"

          echo "svodka schema:"
          sqlite3 testdata/data.sqlite "PRAGMA table_info(svodka);"

          echo "Timeline query check:"
          sqlite3 testdata/data.sqlite "
            SELECT s.date AS date,
                   s.bag_id AS bag_id,
                   COALESCE(b.bag_name, s.bag_id) AS bag_name,
                   SUM(COALESCE(s.orders,0)) AS orders
            FROM svodka s
            LEFT JOIN bags b ON b.bag_id = s.bag_id
            WHERE s.date IS NOT NULL AND s.date != ''
              AND s.bag_id IS NOT NULL AND s.bag_id != ''
            GROUP BY s.date, s.bag_id
            ORDER BY s.date DESC, orders DESC
            LIMIT 5;
          "

          echo "Details query check (one date):"
          d="$(sqlite3 testdata/data.sqlite "SELECT date FROM svodka WHERE date IS NOT NULL AND date != '' ORDER BY date DESC LIMIT 1;")"
          echo "Using date=$d"
          sqlite3 testdata/data.sqlite "
            SELECT s.bag_id AS bag_id,
                   COALESCE(b.bag_name, s.bag_id) AS bag_name,
                   SUM(COALESCE(s.orders,0)) AS orders
            FROM svodka s
            LEFT JOIN bags b ON b.bag_id = s.bag_id
            WHERE s.date='$d' AND s.bag_id IS NOT NULL AND s.bag_id != ''
            GROUP BY s.bag_id
            ORDER BY orders DESC
            LIMIT 5;
          "

      - name: Upload Debug APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
EOF

# -----------------------------
# 5) Commit & push everything needed
# -----------------------------
echo "== Git status before commit =="
git status --porcelain || true

git add \
  gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties \
  app/src/main/java/com/ml/app/data/SQLiteRepo.kt \
  testdata/data.sqlite \
  .github/workflows/android.yml

if git diff --cached --quiet; then
  echo "Nothing to commit (already up-to-date)."
else
  git commit -m "Fix DB joins/metrics, add Gradle wrapper, add CI SQL checks + APK artifact"
fi

echo "== Pushing to origin/main =="
git push origin main

echo "DONE. Open GitHub -> Actions and check the latest run."
