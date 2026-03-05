#!/usr/bin/env bash
set -euo pipefail

BRANCH="stable_build"
GOOD="cce4c14"
FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

# 1) Убедимся, что мы на нужной ветке
git fetch --all
git checkout "$BRANCH"

# 2) Восстановим SummaryScreen.kt из рабочего коммита
git checkout "$GOOD" -- "$FILE"

# 3) Коммит и пуш (если реально были изменения)
git add "$FILE"
if git diff --cached --quiet; then
  echo "Nothing to commit: SummaryScreen.kt already matches $GOOD"
else
  git commit -m "Restore SummaryScreen.kt from $GOOD (fix build)"
  git push origin "$BRANCH"
  echo "DONE: pushed restore commit to $BRANCH"
fi
