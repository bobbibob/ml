#!/usr/bin/env bash
set -euo pipefail

GOOD_COMMIT="e490ca6"

SUMMARY="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"
EDITOR="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

echo "==> Check repo"
git rev-parse --is-inside-work-tree >/dev/null

echo "==> Ensure on stable_build"
git checkout stable_build

echo "==> Restore SummaryScreen.kt from known-good commit: $GOOD_COMMIT"
git checkout "$GOOD_COMMIT" -- "$SUMMARY"

echo "==> Remove date from AddEditArticleScreen (если есть)"
if [ -f "$EDITOR" ]; then
  echo "   Found editor: $EDITOR"
  echo "   Current date-related lines:"
  grep -n "Дата" "$EDITOR" || true

  # Удаляем строки, где рисуется дата (часто это Text("Дата: ...") или label "Дата")
  # 1) прямые вхождения "Дата:"
  sed -i '/"Дата:/d' "$EDITOR"
  # 2) отдельные подписи/лейблы "Дата"
  sed -i '/Text([^)]*"Дата"[^)]*)/d' "$EDITOR" || true
  sed -i '/label[[:space:]]*=[[:space:]]*{[[:space:]]*Text([^)]*"Дата"[^)]*)[[:space:]]*}/d' "$EDITOR" || true

  echo "   After patch date-related lines:"
  grep -n "Дата" "$EDITOR" || true
else
  echo "!! Editor screen not found: $EDITOR"
fi

echo "==> Git status"
git status --porcelain

echo "==> Commit + push"
git add "$SUMMARY" "$EDITOR" 2>/dev/null || true

# Коммитим только если есть изменения
if ! git diff --cached --quiet; then
  git commit -m "Fix: restore SummaryScreen; remove date from article editor"
  git push
  echo "DONE: pushed"
else
  echo "No changes staged; nothing to commit."
fi
