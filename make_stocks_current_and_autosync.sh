#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

screen = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
stock = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")

# ---------- SummaryScreen.kt ----------
s = screen.read_text()

old = '''            is ScreenMode.Stocks -> StockScreen(
              date = state.selectedDate.toString(),
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )'''
new = '''            is ScreenMode.Stocks -> StockScreen(
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )'''
if old in s:
    s = s.replace(old, new, 1)

screen.write_text(s)

# ---------- StockScreen.kt ----------
u = stock.read_text()

# imports
if "import java.time.LocalDate" not in u:
    u = u.replace(
        "import kotlinx.coroutines.launch\n",
        "import kotlinx.coroutines.launch\nimport java.time.LocalDate\n",
        1
    )

# signature
u = u.replace(
'''fun StockScreen(
    date: String,
    refreshKey: String,
    onBack: () -> Unit
) {''',
'''fun StockScreen(
    refreshKey: String,
    onBack: () -> Unit
) {''',
1)

# current effective date
if 'val effectiveDate = LocalDate.now().toString()' not in u:
    u = u.replace(
        '    val repo = remember { SQLiteRepo(ctx) }\n',
        '    val repo = remember { SQLiteRepo(ctx) }\n    val effectiveDate = LocalDate.now().toString()\n',
        1
    )

# reload by current date
u = u.replace(
'''        val stocks = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }''',
'''        val stocks = repo.getResolvedStocksForDate(effectiveDate)
            .groupBy { it.bagId }''',
1)

# effect
u = u.replace(
'''    LaunchedEffect(date, refreshKey) {
        reload()
    }''',
'''    LaunchedEffect(refreshKey) {
        reload()
    }''',
1)

# remove date from title
u = u.replace(
'''        Text(
            text = "Остатки на $date",
            style = MaterialTheme.typography.headlineSmall
        )''',
'''        Text(
            text = "Остатки",
            style = MaterialTheme.typography.headlineSmall
        )''',
1)

u = u.replace(
'''        Text(
            text = "Остатки",
            style = MaterialTheme.typography.headlineSmall
        )''',
'''        Text(
            text = "Остатки",
            style = MaterialTheme.typography.headlineSmall
        )''',
1)

# save overrides using current date
u = u.replace(
'                                            repo.replaceBagStockOverrides(date, bag.bagId, rows)',
'                                            repo.replaceBagStockOverrides(effectiveDate, bag.bagId, rows)',
1)

stock.write_text(u)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/SummaryScreen.kt app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "make stocks screen current state and save with today date"
git push
