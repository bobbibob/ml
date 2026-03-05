#!/usr/bin/env bash
set -euo pipefail

WF_DIR=".github/workflows"
test -d "$WF_DIR" || { echo "Нет папки $WF_DIR"; exit 1; }

echo "==> Workflows:"
ls -1 "$WF_DIR"

# Берём первый workflow файл (если их несколько — можно потом расширить)
WF="$(ls -1 "$WF_DIR"/*.yml "$WF_DIR"/*.yaml 2>/dev/null | head -n 1 || true)"
test -n "${WF:-}" || { echo "Не найдено workflow .yml/.yaml"; exit 1; }

echo "==> Using workflow: $WF"
echo "==> Current 'on:' snippet:"
grep -n -E '^[[:space:]]*on:|^[[:space:]]*push:|^[[:space:]]*pull_request:|^[[:space:]]*branches:' "$WF" || true

# 1) Если есть branches: [ main ] — добавим stable_build
if grep -qE 'branches:[[:space:]]*\[[^]]*\]' "$WF"; then
  if grep -qE 'stable_build' "$WF"; then
    echo "==> stable_build already present in branches[]"
  else
    echo "==> Patching inline branches: [...] to include stable_build"
    sed -i -E 's/(branches:[[:space:]]*\[[^]]*)\]/\1, stable_build]/' "$WF"
  fi

# 2) Если branches списком:
# branches:
#   - main
elif grep -qE '^[[:space:]]*branches:[[:space:]]*$' "$WF"; then
  if grep -qE '^[[:space:]]*-[[:space:]]*stable_build[[:space:]]*$' "$WF"; then
    echo "==> stable_build already present in branches list"
  else
    echo "==> Adding '- stable_build' under branches:"
    # Вставим строку сразу после 'branches:'
    sed -i -E '/^[[:space:]]*branches:[[:space:]]*$/a\
  - stable_build' "$WF"
  fi

# 3) Если вообще нет push triggers — добавим минимальный push trigger
else
  echo "==> No branches filter found. Ensuring push trigger exists for stable_build."
  # Если есть "on:" но нет push, добавим push
  if grep -qE '^[[:space:]]*on:[[:space:]]*$' "$WF" && ! grep -qE '^[[:space:]]*push:[[:space:]]*$' "$WF"; then
    sed -i -E '/^[[:space:]]*on:[[:space:]]*$/a\
  push:\
    branches: [ stable_build ]\
' "$WF"
  fi
fi

echo "==> Updated 'on:' snippet:"
grep -n -E '^[[:space:]]*on:|^[[:space:]]*push:|^[[:space:]]*pull_request:|^[[:space:]]*branches:' "$WF" || true

echo "==> Commit + push"
git status --porcelain
git add "$WF"
if ! git diff --cached --quiet; then
  git commit -m "CI: run build on stable_build branch"
  git push
  echo "DONE: pushed workflow change. Теперь пуши в stable_build будут запускать сборку."
else
  echo "No changes to commit."
fi
