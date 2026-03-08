#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
daily = Path("app/src/main/java/com/ml/app/ui/AddDailySummaryScreen.kt")

# ---------- SQLiteRepo.kt ----------
r = repo.read_text()

if "data class DailySummaryBagSave(" not in r:
    insert = '''

  data class DailySummaryBagSave(
    val bagId: String,
    val ordersByColor: List<Pair<String, Int>>,
    val rkEnabled: Boolean,
    val rkSpend: Double?,
    val rkImpressions: Long?,
    val rkClicks: Long?,
    val rkStake: Double?,
    val igEnabled: Boolean,
    val igSpend: Double?,
    val igImpressions: Long?,
    val igClicks: Long?
  )

  data class DailySummaryDraft(
    val orders: Map<String, Int>,
    val rkEnabled: Map<String, Boolean>,
    val rkSpend: Map<String, String>,
    val rkImpressions: Map<String, String>,
    val rkClicks: Map<String, String>,
    val rkStake: Map<String, String>,
    val igEnabled: Map<String, Boolean>,
    val igSpend: Map<String, String>,
    val igImpressions: Map<String, String>,
    val igClicks: Map<String, String>
  )

  suspend fun loadDailySummaryDraft(date: String): DailySummaryDraft = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val orders = linkedMapOf<String, Int>()
      val rkEnabled = linkedMapOf<String, Boolean>()
      val rkSpend = linkedMapOf<String, String>()
      val rkImpressions = linkedMapOf<String, String>()
      val rkClicks = linkedMapOf<String, String>()
      val rkStake = linkedMapOf<String, String>()
      val igEnabled = linkedMapOf<String, Boolean>()
      val igSpend = linkedMapOf<String, String>()
      val igImpressions = linkedMapOf<String, String>()
      val igClicks = linkedMapOf<String, String>()

      db.rawQuery(
        """
        SELECT bag_id, color, orders
        FROM svodka
        WHERE date=?
          AND bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
          AND color NOT IN ('__TOTAL__','TOTAL')
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iOrders = c.getColumnIndexOrThrow("orders")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          val value = if (c.isNull(iOrders)) 0 else c.getDouble(iOrders).toInt()
          orders["$bagId::$color"] = value
        }
      }

      db.rawQuery(
        """
        SELECT
          bag_id,
          rk_spend, rk_impressions, rk_clicks, stake_pct,
          ig_spend, ig_impressions, ig_clicks
        FROM svodka
        WHERE date=?
          AND color='__TOTAL__'
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iRkSpend = c.getColumnIndexOrThrow("rk_spend")
        val iRkImp = c.getColumnIndexOrThrow("rk_impressions")
        val iRkClicks = c.getColumnIndexOrThrow("rk_clicks")
        val iStake = c.getColumnIndexOrThrow("stake_pct")
        val iIgSpend = c.getColumnIndexOrThrow("ig_spend")
        val iIgImp = c.getColumnIndexOrThrow("ig_impressions")
        val iIgClicks = c.getColumnIndexOrThrow("ig_clicks")

        while (c.moveToNext()) {
          val bagId = c.getString(iBag)

          val rkSpendVal = if (c.isNull(iRkSpend)) 0.0 else c.getDouble(iRkSpend)
          val rkImpVal = if (c.isNull(iRkImp)) 0L else c.getDouble(iRkImp).toLong()
          val rkClicksVal = if (c.isNull(iRkClicks)) 0L else c.getDouble(iRkClicks).toLong()
          val stakeVal = if (c.isNull(iStake)) 0.0 else c.getDouble(iStake)

          val igSpendVal = if (c.isNull(iIgSpend)) 0.0 else c.getDouble(iIgSpend)
          val igImpVal = if (c.isNull(iIgImp)) 0L else c.getDouble(iIgImp).toLong()
          val igClicksVal = if (c.isNull(iIgClicks)) 0L else c.getDouble(iIgClicks).toLong()

          rkEnabled[bagId] = rkSpendVal != 0.0 || rkImpVal != 0L || rkClicksVal != 0L || stakeVal != 0.0
          rkSpend[bagId] = if (rkSpendVal == 0.0) "" else rkSpendVal.toString()
          rkImpressions[bagId] = if (rkImpVal == 0L) "" else rkImpVal.toString()
          rkClicks[bagId] = if (rkClicksVal == 0L) "" else rkClicksVal.toString()
          rkStake[bagId] = if (stakeVal == 0.0) "" else stakeVal.toString()

          igEnabled[bagId] = igSpendVal != 0.0 || igImpVal != 0L || igClicksVal != 0L
          igSpend[bagId] = if (igSpendVal == 0.0) "" else igSpendVal.toString()
          igImpressions[bagId] = if (igImpVal == 0L) "" else igImpVal.toString()
          igClicks[bagId] = if (igClicksVal == 0L) "" else igClicksVal.toString()
        }
      }

      DailySummaryDraft(
        orders = orders,
        rkEnabled = rkEnabled,
        rkSpend = rkSpend,
        rkImpressions = rkImpressions,
        rkClicks = rkClicks,
        rkStake = rkStake,
        igEnabled = igEnabled,
        igSpend = igSpend,
        igImpressions = igImpressions,
        igClicks = igClicks
      )
    }
  }

  suspend fun saveDailySummary(
    date: String,
    bags: List<DailySummaryBagSave>
  ) = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      db.beginTransaction()
      try {
        for (bag in bags) {
          val totalOrders = bag.ordersByColor.sumOf { it.second }

          for ((color, orders) in bag.ordersByColor) {
            db.execSQL(
              """
              INSERT INTO svodka(date, bag, color, orders, source)
              VALUES(?,?,?,?,?)
              ON CONFLICT(date, bag, color) DO UPDATE SET
                orders=excluded.orders,
                source=excluded.source
              """.trimIndent(),
              arrayOf(date, bag.bagId, color, orders.toDouble(), "android-app")
            )
          }

          db.execSQL(
            """
            INSERT INTO svodka(
              date, bag, color, orders, source,
              rk_spend, rk_impressions, rk_clicks, stake_pct,
              ig_spend, ig_impressions, ig_clicks
            )
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(date, bag, color) DO UPDATE SET
              orders=excluded.orders,
              source=excluded.source,
              rk_spend=excluded.rk_spend,
              rk_impressions=excluded.rk_impressions,
              rk_clicks=excluded.rk_clicks,
              stake_pct=excluded.stake_pct,
              ig_spend=excluded.ig_spend,
              ig_impressions=excluded.ig_impressions,
              ig_clicks=excluded.ig_clicks
            """.trimIndent(),
            arrayOf(
              date,
              bag.bagId,
              "__TOTAL__",
              totalOrders.toDouble(),
              "android-app",
              if (bag.rkEnabled) bag.rkSpend ?: 0.0 else 0.0,
              if (bag.rkEnabled) (bag.rkImpressions ?: 0L).toDouble() else 0.0,
              if (bag.rkEnabled) (bag.rkClicks ?: 0L).toDouble() else 0.0,
              if (bag.rkEnabled) bag.rkStake ?: 0.0 else 0.0,
              if (bag.igEnabled) bag.igSpend ?: 0.0 else 0.0,
              if (bag.igEnabled) (bag.igImpressions ?: 0L).toDouble() else 0.0,
              if (bag.igEnabled) (bag.igClicks ?: 0L).toDouble() else 0.0
            )
          )
        }

        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
    }
  }

'''
    last = r.rfind("\n}")
    if last == -1:
        raise SystemExit("SQLiteRepo end not found")
    r = r[:last] + insert + "\n}"
    repo.write_text(r)

# ---------- AddDailySummaryScreen.kt ----------
u = daily.read_text()

if "import androidx.compose.runtime.rememberCoroutineScope" not in u:
    u = u.replace(
        "import androidx.compose.runtime.remember\n",
        "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n",
        1
    )

if "import com.ml.app.data.PackUploadManager" not in u:
    u = u.replace(
        "import coil.compose.AsyncImage\n",
        "import coil.compose.AsyncImage\nimport com.ml.app.data.PackUploadManager\n",
        1
    )

if "import kotlinx.coroutines.launch" not in u:
    u = u.replace(
        "import java.time.LocalDate\n",
        "import java.time.LocalDate\nimport kotlinx.coroutines.launch\n",
        1
    )

u = u.replace(
    "    val repo = remember { SQLiteRepo(ctx) }\n",
    "    val repo = remember { SQLiteRepo(ctx) }\n    val scope = rememberCoroutineScope()\n",
    1
)

u = u.replace(
'''    LaunchedEffect(Unit) {
        val meta = repo.listSummaryBagColorMeta()
        items.clear()
        items.addAll(
            meta.map {
                DailySummaryBagUi(
                    bagId = it.bagId,
                    bagName = it.bagName,
                    photoPath = it.photoPath,
                    colors = it.colors.sortedBy { c -> c.lowercase() }
                )
            }
        )

        for (bag in items) {
            for (color in bag.colors) {
                orders.putIfAbsent("${bag.bagId}::$color", 0)
            }

            rkEnabled.putIfAbsent(bag.bagId, false)
            rkSpend.putIfAbsent(bag.bagId, "")
            rkImpressions.putIfAbsent(bag.bagId, "")
            rkClicks.putIfAbsent(bag.bagId, "")
            rkStake.putIfAbsent(bag.bagId, "")

            igEnabled.putIfAbsent(bag.bagId, false)
            igSpend.putIfAbsent(bag.bagId, "")
            igImpressions.putIfAbsent(bag.bagId, "")
            igClicks.putIfAbsent(bag.bagId, "")
        }
    }''',
'''    suspend fun loadForDate() {
        val meta = repo.listSummaryBagColorMeta()
        items.clear()
        items.addAll(
            meta.map {
                DailySummaryBagUi(
                    bagId = it.bagId,
                    bagName = it.bagName,
                    photoPath = it.photoPath,
                    colors = it.colors.sortedBy { c -> c.lowercase() }
                )
            }
        )

        orders.clear()
        rkEnabled.clear()
        rkSpend.clear()
        rkImpressions.clear()
        rkClicks.clear()
        rkStake.clear()
        igEnabled.clear()
        igSpend.clear()
        igImpressions.clear()
        igClicks.clear()

        for (bag in items) {
            for (color in bag.colors) {
                orders["${bag.bagId}::$color"] = 0
            }

            rkEnabled[bag.bagId] = false
            rkSpend[bag.bagId] = ""
            rkImpressions[bag.bagId] = ""
            rkClicks[bag.bagId] = ""
            rkStake[bag.bagId] = ""

            igEnabled[bag.bagId] = false
            igSpend[bag.bagId] = ""
            igImpressions[bag.bagId] = ""
            igClicks[bag.bagId] = ""
        }

        val draft = repo.loadDailySummaryDraft(selectedDate.toString())

        for ((k, v) in draft.orders) orders[k] = v
        for ((k, v) in draft.rkEnabled) rkEnabled[k] = v
        for ((k, v) in draft.rkSpend) rkSpend[k] = v
        for ((k, v) in draft.rkImpressions) rkImpressions[k] = v
        for ((k, v) in draft.rkClicks) rkClicks[k] = v
        for ((k, v) in draft.rkStake) rkStake[k] = v
        for ((k, v) in draft.igEnabled) igEnabled[k] = v
        for ((k, v) in draft.igSpend) igSpend[k] = v
        for ((k, v) in draft.igImpressions) igImpressions[k] = v
        for ((k, v) in draft.igClicks) igClicks[k] = v
    }

    LaunchedEffect(selectedDate) {
        loadForDate()
    }''',
    1
)

u = u.replace(
'''                Button(
                    onClick = { onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Дальше будет сохранение")
                }''',
'''                Button(
                    onClick = {
                        scope.launch {
                            val bags = items.map { bag ->
                                com.ml.app.data.SQLiteRepo.DailySummaryBagSave(
                                    bagId = bag.bagId,
                                    ordersByColor = bag.colors.map { color ->
                                        color to (orders["${bag.bagId}::$color"] ?: 0)
                                    },
                                    rkEnabled = rkEnabled[bag.bagId] == true,
                                    rkSpend = rkSpend[bag.bagId]?.replace(",", ".")?.toDoubleOrNull(),
                                    rkImpressions = rkImpressions[bag.bagId]?.toLongOrNull(),
                                    rkClicks = rkClicks[bag.bagId]?.toLongOrNull(),
                                    rkStake = rkStake[bag.bagId]?.replace(",", ".")?.toDoubleOrNull(),
                                    igEnabled = igEnabled[bag.bagId] == true,
                                    igSpend = igSpend[bag.bagId]?.replace(",", ".")?.toDoubleOrNull(),
                                    igImpressions = igImpressions[bag.bagId]?.toLongOrNull(),
                                    igClicks = igClicks[bag.bagId]?.toLongOrNull()
                                )
                            }

                            repo.saveDailySummary(selectedDate.toString(), bags)
                            PackUploadManager.saveUserChangesAndUpload(ctx)
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сохранить сводку")
                }''',
    1
)

daily.write_text(u)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt app/src/main/java/com/ml/app/ui/AddDailySummaryScreen.kt
git commit -m "add daily summary save and autosync"
git push
