#!/usr/bin/env bash
set -euo pipefail

A="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
S="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

cp "$A" "$A.bak.lock_colors.$(date +%s)"
cp "$S" "$S.bak.details_refresh.$(date +%s)"

python3 - <<'PY'
from pathlib import Path
import re

# ---------- AddEditArticleScreen ----------
p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8")

# import Color if missing
if 'import androidx.compose.ui.graphics.Color\n' not in s:
    if 'import androidx.compose.ui.graphics.Brush\n' in s:
        s = s.replace(
            'import androidx.compose.ui.graphics.Brush\n',
            'import androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\n',
            1
        )
    elif 'import androidx.compose.ui.unit.dp\n' in s:
        s = s.replace(
            'import androidx.compose.ui.unit.dp\n',
            'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.unit.dp\n',
            1
        )

# прямой enabled на полях sku
s = s.replace('enabled = canEditColorSku,', 'enabled = articleBase.trim().isNotBlank(),')
s = s.replace('enabled = canEditColorSku', 'enabled = articleBase.trim().isNotBlank()')
s = s.replace(
    'alpha(if (canEditColorSku) 1f else 0.5f)',
    'alpha(if (articleBase.trim().isNotBlank()) 1f else 0.5f)'
)
s = s.replace('if (!canEditColorSku) {', 'if (articleBase.trim().isBlank()) {')
s = s.replace('if (canEditColorSku) {', 'if (articleBase.trim().isNotBlank()) {')
s = re.sub(r'\bcanEditColorSku\b', 'articleBase.trim().isNotBlank()', s)

# жёстко блокируем открытие dropdown/picker в проблемной зоне файла
lines = s.splitlines()
start = max(0, 640 - 1)
end = min(len(lines), 730)

for i in range(start, end):
    line = lines[i]

    # expanded = true / false toggle
    if re.search(r'\bexpanded\s*=\s*true\b', line) and 'articleBase.trim().isNotBlank()' not in line:
        indent = re.match(r'^(\s*)', line).group(1)
        core = line.strip()
        lines[i] = f'{indent}if (articleBase.trim().isNotBlank()) {{ {core} }}'

    if re.search(r'\bexpanded\s*=\s*!\s*expanded\b', line) and 'articleBase.trim().isNotBlank()' not in line:
        indent = re.match(r'^(\s*)', line).group(1)
        core = line.strip()
        lines[i] = f'{indent}if (articleBase.trim().isNotBlank()) {{ {core} }}'

    # show picker/menu booleans
    if re.search(r'\bshow\w*\s*=\s*true\b', line) and 'articleBase.trim().isNotBlank()' not in line:
        indent = re.match(r'^(\s*)', line).group(1)
        core = line.strip()
        lines[i] = f'{indent}if (articleBase.trim().isNotBlank()) {{ {core} }}'

s = "\n".join(lines)

p.write_text(s, encoding="utf-8")

# ---------- SummaryScreen ----------
p = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
s = p.read_text(encoding="utf-8")

# 1) принудительно обновлять details при входе на экран деталей
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

# 2) убрать белый экран: если rows пустые, показываем явный текст
old = '''private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>
) {
  LazyColumn(
'''
new = '''private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>
) {
  if (rows.isEmpty()) {
    Box(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      Text("Нет данных по этой дате", color = Color.Gray)
    }
    return
  }

  LazyColumn(
'''
if old in s:
    s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")

print("PATCH_OK")
PY

echo '=== VERIFY ARTICLE SCREEN ==='
grep -n 'articleBase.trim().isNotBlank()\|expanded = true\|expanded = !expanded\|show.*= true' "$A" | sed -n '1,80p' || true

echo '-----'
echo '=== VERIFY SUMMARY SCREEN ==='
grep -n 'LaunchedEffect(state.mode, state.selectedDate)\|Нет данных по этой дате' "$S" || true
