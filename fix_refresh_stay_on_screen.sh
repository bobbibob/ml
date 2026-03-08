#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
screen = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
stock = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")

# ---------- SummaryViewModel.kt ----------
s = vm.read_text()

old_refresh_timeline = '''        _state.value = _state.value.copy(
          loading = true,
          status = "Loading…",
          mode = ScreenMode.Timeline
        )
'''
new_refresh_timeline = '''        _state.value = _state.value.copy(
          loading = true,
          status = "Loading…"
        )
'''
if old_refresh_timeline in s:
    s = s.replace(old_refresh_timeline, new_refresh_timeline, 1)

old_after = '''  private fun refreshAfterSync() {
    when (state.value.mode) {
      is ScreenMode.Timeline -> refreshTimeline()
      is ScreenMode.Details -> refreshDetails()
      is ScreenMode.Stocks -> refreshTimeline()
      is ScreenMode.ArticleEditor -> {
        // stay on editor
      }
    }
  }
'''
new_after = '''  private fun refreshAfterSync() {
    when (state.value.mode) {
      is ScreenMode.Timeline -> refreshTimeline()
      is ScreenMode.Details -> refreshDetails()
      is ScreenMode.Stocks -> {
        _state.value = _state.value.copy(status = "Updated")
      }
      is ScreenMode.ArticleEditor -> {
        _state.value = _state.value.copy(status = "Updated")
      }
    }
  }
'''
if old_after in s:
    s = s.replace(old_after, new_after, 1)

vm.write_text(s)

# ---------- StockScreen.kt ----------
u = stock.read_text()

u = u.replace(
'''fun StockScreen(
    date: String,
    onBack: () -> Unit
) {''',
'''fun StockScreen(
    date: String,
    refreshKey: String,
    onBack: () -> Unit
) {''',
1)

u = u.replace(
'''    LaunchedEffect(date) {
        reload()
    }''',
'''    LaunchedEffect(date, refreshKey) {
        reload()
    }''',
1)

stock.write_text(u)

# ---------- SummaryScreen.kt ----------
q = screen.read_text()

q = q.replace(
'''            is ScreenMode.Stocks -> StockScreen(
              date = state.selectedDate.toString(),
              onBack = { vm.backFromStocks() }
            )''',
'''            is ScreenMode.Stocks -> StockScreen(
              date = state.selectedDate.toString(),
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )''',
1)

screen.write_text(q)

print("patched")
PY

git add app/src/main/java/com/ml/app/ui/SummaryViewModel.kt \
        app/src/main/java/com/ml/app/ui/SummaryScreen.kt \
        app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "keep current screen on refresh and reload stocks in place" || true
git push
