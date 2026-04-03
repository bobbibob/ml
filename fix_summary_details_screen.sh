#!/usr/bin/env bash
set -euo pipefail

VM="app/src/main/java/com/ml/app/ui/SummaryViewModel.kt"
SC="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

cp "$VM" "$VM.bak.details_screen.$(date +%s)"
cp "$SC" "$SC.bak.details_screen.$(date +%s)"

python3 - <<'PY'
from pathlib import Path

# ---------- SummaryViewModel ----------
p = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
s = p.read_text(encoding="utf-8")

old = '''        _state.value = _state.value.copy(
          rows = rowsWithResolvedStock,
          cardTypes = types,
          loading = false,
          status = "OK"
        )
'''

new = '''        _state.value = _state.value.copy(
          rows = rowsWithResolvedStock,
          cardTypes = types,
          loading = false,
          status = "DETAILS date=$date rows=${rows.size} resolved=${rowsWithResolvedStock.size}"
        )
'''

if old in s:
    s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")

# ---------- SummaryScreen ----------
p = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
s = p.read_text(encoding="utf-8")

marker = '''  LaunchedEffect(tasksVm.state.currentUser?.user_id) {
    if (tasksVm.state.currentUser != null) {
      vm.init()
      vm.syncServerSummaries()
    }
  }
'''

insert = marker + '''

  LaunchedEffect(state.mode, state.selectedDate) {
    if (state.mode is ScreenMode.Details) {
      vm.refreshDetails()
    }
  }
'''

if marker in s and 'LaunchedEffect(state.mode, state.selectedDate)' not in s:
    s = s.replace(marker, insert, 1)

old2 = '''private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>
) {
  LazyColumn(
'''

new2 = '''private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>
) {
  if (rows.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      Text("Нет данных по этой дате", color = Color.Gray)
    }
    return
  }

  LazyColumn(
'''

if old2 in s:
    s = s.replace(old2, new2, 1)

p.write_text(s, encoding="utf-8")
print("PATCH_OK")
PY

echo '=== VERIFY SUMMARY DETAILS PATCH ==='
grep -n 'LaunchedEffect(state.mode, state.selectedDate)\|Нет данных по этой дате\|DETAILS date=' "$VM" "$SC" || true
