#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

repo = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
screen = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
stock = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")

# ---------- SQLiteRepo.kt ----------
r = repo.read_text()

if "data class StockResolvedRow(" not in r:
    insert = '''

  data class StockResolvedRow(
    val bagId: String,
    val color: String,
    val stock: Double
  )

  suspend fun getResolvedStocksForDate(date: String): List<StockResolvedRow> = withContext(Dispatchers.IO) {
    openDbReadWrite().use { db ->
      val base = LinkedHashMap<Pair<String, String>, Double>()

      db.rawQuery(
        """
        SELECT bag_id, color, stock
        FROM svodka
        WHERE date=?
          AND bag_id IS NOT NULL AND bag_id!=''
          AND color IS NOT NULL AND color!=''
          AND color NOT IN ('__TOTAL__','TOTAL')
        ORDER BY bag_id, color
        """.trimIndent(),
        arrayOf(date)
      ).use { c ->
        val iBag = c.getColumnIndexOrThrow("bag_id")
        val iColor = c.getColumnIndexOrThrow("color")
        val iStock = c.getColumnIndexOrThrow("stock")
        while (c.moveToNext()) {
          val bagId = c.getString(iBag)
          val color = c.getString(iColor)
          val stock = if (c.isNull(iStock)) 0.0 else c.getDouble(iStock)
          base[bagId to color] = stock
        }
      }

      val overrides = getEffectiveStockOverrides(date)
      for (o in overrides) {
        base[o.bagId to o.color] = o.stock
      }

      base.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map {
          StockResolvedRow(
            bagId = it.key.first,
            color = it.key.second,
            stock = it.value
          )
        }
    }
  }

'''
    last = r.rfind("\n}")
    if last == -1:
        raise SystemExit("SQLiteRepo end not found")
    r = r[:last] + insert + "\n}"
    repo.write_text(r)

# ---------- StockScreen.kt ----------
stock.write_text('''package com.ml.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.ml.app.data.PackUploadManager
import com.ml.app.data.SQLiteRepo
import kotlinx.coroutines.launch

private data class StockBagUi(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<Pair<String, Double>>
)

@Composable
fun StockScreen(
    date: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<StockBagUi>>(emptyList()) }
    var editingBagId by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }

    suspend fun reload() {
        val meta = repo.loadTimeline(180)
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
    }

    LaunchedEffect(date) {
        reload()
    }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "Остатки на $date",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

                                for ((color, stock) in bag.colors) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = color,
                                            modifier = Modifier.weight(1f)
                                        )

                                        if (editingBagId == bag.bagId) {
                                            val key = "${bag.bagId}::$color"
                                            OutlinedTextField(
                                                value = drafts[key] ?: stock.toString(),
                                                onValueChange = { drafts[key] = it },
                                                modifier = Modifier.width(120.dp),
                                                singleLine = true
                                            )
                                        } else {
                                            Text(stock.toString())
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (editingBagId == bag.bagId) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val rows = bag.colors.map { (color, stock) ->
                                            val key = "${bag.bagId}::$color"
                                            color to ((drafts[key] ?: stock.toString()).replace(",", ".").toDoubleOrNull() ?: stock)
                                        }
                                        repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                        PackUploadManager.saveUserChangesAndUpload(ctx)
                                        editingBagId = null
                                        drafts.clear()
                                        reload()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Сохранить остатки")
                            }
                        } else {
                            Button(
                                onClick = {
                                    editingBagId = bag.bagId
                                    drafts.clear()
                                    for ((color, stock) in bag.colors) {
                                        drafts["${bag.bagId}::$color"] = stock.toString()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Редактировать")
                            }
                        }
                    }
                }
            }
        }
    }
}
''')

# ---------- SummaryViewModel.kt ----------
v = vm.read_text()

if "data object Stocks : ScreenMode()" not in v:
    v = v.replace(
        "  data object Timeline : ScreenMode()\n",
        "  data object Timeline : ScreenMode()\n  data object Stocks : ScreenMode()\n",
        1
    )

if "fun openStocks()" not in v:
    anchor = '''  fun backFromArticleEditor() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
'''
    insert = '''  fun backFromArticleEditor() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }

  fun openStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Stocks)
  }

  fun backFromStocks() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
'''
    if anchor not in v:
        raise SystemExit("SummaryViewModel anchor not found")
    v = v.replace(anchor, insert, 1)

if "is ScreenMode.Stocks -> refreshTimeline()" not in v:
    v = v.replace(
        "      is ScreenMode.Details -> refreshDetails()\n",
        "      is ScreenMode.Details -> refreshDetails()\n      is ScreenMode.Stocks -> refreshTimeline()\n",
        1
    )

vm.write_text(v)

# ---------- SummaryScreen.kt ----------
u = screen.read_text()

if "is ScreenMode.Stocks -> StockScreen(" not in u:
    u = u.replace(
        '''            is ScreenMode.ArticleEditor -> AddEditArticleScreen(
              bagId = (state.mode as ScreenMode.ArticleEditor).bagId,
              onDone = { vm.backFromArticleEditor() }
            )
''',
        '''            is ScreenMode.ArticleEditor -> AddEditArticleScreen(
              bagId = (state.mode as ScreenMode.ArticleEditor).bagId,
              onDone = { vm.backFromArticleEditor() }
            )

            is ScreenMode.Stocks -> StockScreen(
              date = state.selectedDate.toString(),
              onBack = { vm.backFromStocks() }
            )
''',
        1
    )

# backhandler
u = u.replace(
    '''  BackHandler {
    when (state.mode) {
      is ScreenMode.Details -> vm.backToTimeline()
      is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
      else -> showExitAppDialog = true
    }
  }
''',
    '''  BackHandler {
    when (state.mode) {
      is ScreenMode.Details -> vm.backToTimeline()
      is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
      is ScreenMode.Stocks -> vm.backFromStocks()
      else -> showExitAppDialog = true
    }
  }
''',
    1
)

# bottom bar signature and usage
u = u.replace(
    '''      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )
''',
    '''      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        onStocksClick = { vm.openStocks() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )
''',
    1
)

u = u.replace(
    '''private fun ArticleBottomBar(
  onArticleClick: () -> Unit,
  modifier: Modifier = Modifier
) {''',
    '''private fun ArticleBottomBar(
  onArticleClick: () -> Unit,
  onStocksClick: () -> Unit,
  modifier: Modifier = Modifier
) {''',
    1
)

u = u.replace(
    '''    Button(
      onClick = onArticleClick,
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)
    ) {
      Text("Добавить/редактировать артикул")
    }
''',
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
    }
''',
    1
)

screen.write_text(u)

print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt \
        app/src/main/java/com/ml/app/ui/StockScreen.kt \
        app/src/main/java/com/ml/app/ui/SummaryViewModel.kt \
        app/src/main/java/com/ml/app/ui/SummaryScreen.kt
git commit -m "add stock screen with inline editing"
git push
