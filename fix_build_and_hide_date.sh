#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

echo "==> Ensuring we're in a git repo"
git rev-parse --is-inside-work-tree >/dev/null

echo "==> Fetch origin"
git fetch origin --prune

echo "==> Restore SummaryScreen.kt from origin/stable_build (last known good)"
# если ветка называется иначе — замени origin/stable_build на origin/main или нужную
git checkout origin/stable_build -- "$FILE"

echo "==> Verify date line exists"
grep -n 'Text("Дата:' "$FILE" || true

echo "==> Patch: hide date ONLY when editor is visible (ScreenMode.EDITOR)"
# 1) самый частый формат (в одну строку)
if grep -q 'Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)' "$FILE"; then
  sed -i 's/Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)/if (state.screenMode != ScreenMode.EDITOR) Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)/' "$FILE"
else
  # 2) запасной вариант: если формат чуть другой (например пробелы/переносы)
  # Попробуем заменить любую строку, где есть Text("Дата: ${state.selectedDate}"
  # ВАЖНО: этот вариант предполагает, что Text("Дата...") тоже в одну строку.
  sed -i 's/^([[:space:]]*)Text\("Дата: \$\{state\.selectedDate\}.*$/\1if (state.screenMode != ScreenMode.EDITOR) &/' "$FILE" || true
fi

echo "==> Quick check: make sure ScreenMode.EDITOR exists"
grep -n "ScreenMode" "$FILE" | head -n 20 || true

echo "==> Git status (only important files)"
git status --porcelain

echo "==> Commit + push"
git add "$FILE"
git commit -m "Fix: restore SummaryScreen and hide date in editor" || echo "No changes to commit."
git push

echo "DONE"
