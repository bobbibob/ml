#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

screen = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
new_screen = Path("app/src/main/java/com/ml/app/ui/AddDailySummaryScreen.kt")
repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")

# ---------- SQLiteRepo.kt ----------
r = repo.read_text()

if "data class SummaryBagColorMeta(" not in r:
    insert = '''

  data class SummaryBagColorMeta(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<String>
  )

  suspend fun listSummaryBagColorMeta(): List<SummaryBagColorMeta> = withContext(Dispatchers.IO) {
    val bagMeta = listStockBagMeta()

    val bagToColors = LinkedHashMap<String, MutableSet<String>>()

    openDbReadWrite().use { db ->
      db.rawQuery(
        """
        SELECT bag_id, color
        FROM bag_user_colors
        WHERE bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
        ORDER BY bag_id, color
        """.trimIndent(),
        null
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          bagToColors.getOrPut(bagId) { linkedSetOf() }.add(color)
        }
      }

      db.rawQuery(
        """
        SELECT DISTINCT bag_id, color
        FROM svodka
        WHERE bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
          AND color NOT IN ('__TOTAL__','TOTAL')
        ORDER BY bag_id, color
        """.trimIndent(),
        null
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          bagToColors.getOrPut(bagId) { linkedSetOf() }.add(color)
        }
      }
    }

    bagMeta.mapNotNull { bag ->
      val colors = bagToColors[bag.bagId]?.toList().orEmpty()
      if (colors.isEmpty()) null
      else SummaryBagColorMeta(
        bagId = bag.bagId,
        bagName = bag.bagName,
        photoPath = bag.photoPath,
        colors = colors
      )
    }
  }

'''
    last = r.rfind("\n}")
    if last == -1:
        raise SystemExit("SQLiteRepo end not found")
    r = r[:last] + insert + "\n}"
    repo.write_text(r)

# ---------- SummaryViewModel.kt ----------
v = vm.read_text()

if "data object AddDailySummary : ScreenMode()" not in v:
    v = v.replace(
        "  data object Stocks : ScreenMode()\n",
        "  data object Stocks : ScreenMode()\n  data object AddDailySummary : ScreenMode()\n",
        1
    )

if "fun openAddDailySummary()" not in v:
    anchor = '''  fun backFromStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
'''
    insert = '''  fun backFromStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun openAddDailySummary() {
    _state.value = _state.value.copy(mode = ScreenMode.AddDailySummary)
  }

  fun backFromAddDailySummary() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
'''
    if anchor not in v:
        raise SystemExit("SummaryViewModel anchor not found")
    v = v.replace(anchor, insert, 1)

if "is ScreenMode.AddDailySummary -> {" not in v:
    v = v.replace(
        "      is ScreenMode.Stocks -> {\n        _state.value = _state.value.copy(status = \"Updated\")\n      }\n",
        "      is ScreenMode.Stocks -> {\n        _state.value = _state.value.copy(status = \"Updated\")\n      }\n"
        "      is ScreenMode.AddDailySummary -> {\n        _state.value = _state.value.copy(status = \"Updated\")\n      }\n",
        1
    )

vm.write_text(v)

# ---------- AddDailySummaryScreen.kt ----------
new_screen.write_text('''package com.ml.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ml.app.data.SQLiteRepo
import java.time.LocalDate

private data class DailySummaryBagUi(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<String>
)

@Composable
fun AddDailySummaryScreen(
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }

    var selectedDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    val items = remember { mutableStateListOf<DailySummaryBagUi>() }
    val orders = remember { mutableStateMapOf<String, Int>() }

    var rkEnabled by remember { mutableStateOf(false) }
    var rkSpend by remember { mutableStateOf("") }
    var rkImpressions by remember { mutableStateOf("") }
    var rkClicks by remember { mutableStateOf("") }
    var rkStake by remember { mutableStateOf("") }

    var igEnabled by remember { mutableStateOf(false) }
    var igSpend by remember { mutableStateOf("") }
    var igImpressions by remember { mutableStateOf("") }
    var igClicks by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
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
        }
    }

    BackHandler { onBack() }

    fun openDatePicker() {
        DatePickerDialog(
            ctx,
            { _, y, m, d ->
                selectedDate = LocalDate.of(y, m + 1, d)
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "Добавить сводку",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { openDatePicker() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Дата: $selectedDate")
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.bagId }) { bag ->
                Card(
                    colors = CardDefaults.cardColors(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (!bag.photoPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = bag.photoPath,
                                    contentDescription = bag.bagName,
                                    modifier = Modifier
                                        .width(92.dp)
                                        .height(92.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bag.bagName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                for (color in bag.colors) {
                                    val key = "${bag.bagId}::$color"
                                    val value = orders[key] ?: 0

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = color,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedButton(
                                            onClick = { orders[key] = maxOf(0, value - 1) }
                                        ) {
                                            Text("-")
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = value.toString(),
                                            modifier = Modifier.width(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))

                                        OutlinedButton(
                                            onClick = { orders[key] = value + 1 }
                                        ) {
                                            Text("+")
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Расходы",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "РК",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rkEnabled,
                                onCheckedChange = { rkEnabled = it }
                            )
                            Text("Включить РК")
                        }

                        OutlinedTextField(
                            value = rkSpend,
                            onValueChange = { rkSpend = it },
                            enabled = rkEnabled,
                            label = { Text("Расход") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkImpressions,
                            onValueChange = { rkImpressions = it.filter { ch -> ch.isDigit() } },
                            enabled = rkEnabled,
                            label = { Text("Показы") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkClicks,
                            onValueChange = { rkClicks = it.filter { ch -> ch.isDigit() } },
                            enabled = rkEnabled,
                            label = { Text("Клики") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkStake,
                            onValueChange = { rkStake = it },
                            enabled = rkEnabled,
                            label = { Text("Ставка") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Instagram",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = igEnabled,
                                onCheckedChange = { igEnabled = it }
                            )
                            Text("Включить Instagram")
                        }

                        OutlinedTextField(
                            value = igSpend,
                            onValueChange = { igSpend = it },
                            enabled = igEnabled,
                            label = { Text("Расход") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = igImpressions,
                            onValueChange = { igImpressions = it.filter { ch -> ch.isDigit() } },
                            enabled = igEnabled,
                            label = { Text("Показы") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = igClicks,
                            onValueChange = { igClicks = it.filter { ch -> ch.isDigit() } },
                            enabled = igEnabled,
                            label = { Text("Клики") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Дальше будет сохранение")
                        }
                    }
                }
            }
        }
    }
}
''')

# ---------- SummaryScreen.kt ----------
u = screen.read_text()

u = u.replace(
    '''      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        onStocksClick = { vm.openStocks() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )''',
    '''      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        onAddSummaryClick = { vm.openAddDailySummary() },
        onStocksClick = { vm.openStocks() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )''',
    1
)

u = u.replace(
    '''private fun ArticleBottomBar(
  onArticleClick: () -> Unit,
  onStocksClick: () -> Unit,
  modifier: Modifier = Modifier
) {''',
    '''private fun ArticleBottomBar(
  onArticleClick: () -> Unit,
  onAddSummaryClick: () -> Unit,
  onStocksClick: () -> Unit,
  modifier: Modifier = Modifier
) {''',
    1
)

u = u.replace(
    '''    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Button(
        onClick = onArticleClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Артикулы")
      }

      Button(
        onClick = onStocksClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Остатки")
      }
    }''',
    '''    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(
        onClick = onArticleClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Артикулы")
      }

      Button(
        onClick = onAddSummaryClick,
        modifier = Modifier.width(56.dp).height(56.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(0.dp)
      ) {
        Text("+")
      }

      Button(
        onClick = onStocksClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Остатки")
      }
    }''',
    1
)

if "is ScreenMode.AddDailySummary -> AddDailySummaryScreen(" not in u:
    u = u.replace(
        '''            is ScreenMode.Stocks -> StockScreen(
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )''',
        '''            is ScreenMode.Stocks -> StockScreen(
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )

            is ScreenMode.AddDailySummary -> AddDailySummaryScreen(
              onBack = { vm.backFromAddDailySummary() }
            )''',
        1
    )

u = u.replace(
    '''  BackHandler {
    when (state.mode) {
      is ScreenMode.Details -> vm.backToTimeline()
      is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
      is ScreenMode.Stocks -> vm.backFromStocks()
      else -> showExitAppDialog = true
    }
  }''',
    '''  BackHandler {
    when (state.mode) {
      is ScreenMode.Details -> vm.backToTimeline()
      is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
      is ScreenMode.Stocks -> vm.backFromStocks()
      is ScreenMode.AddDailySummary -> vm.backFromAddDailySummary()
      else -> showExitAppDialog = true
    }
  }''',
    1
)

screen.write_text(u)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt \
        app/src/main/java/com/ml/app/ui/SummaryViewModel.kt \
        app/src/main/java/com/ml/app/ui/SummaryScreen.kt \
        app/src/main/java/com/ml/app/ui/AddDailySummaryScreen.kt
git commit -m "add daily summary screen ui with plus button"
git push
