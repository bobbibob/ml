#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
if [[ ! -f "$FILE" ]]; then
  echo "ERROR: not found: $FILE"
  exit 1
fi

echo "==> Searching for date UI usage inside AddEditArticleScreen..."
grep -nE 'Дата:|selectedDate|Date:' "$FILE" || true

# 1) Самый безопасный способ: удалить/закомментировать именно строку Text("Дата: ...") если она есть в этом файле
# (не ломаем общий хедер приложения, только если дата рисуется прямо в AddEditArticleScreen)
if grep -q 'Text("Дата:' "$FILE"; then
  echo "==> Commenting out Text(\"Дата:\") line(s) in AddEditArticleScreen.kt"
  # Комментируем строки, где текст начинается с "Дата:"
  sed -i -E 's/^([[:space:]]*)Text\("Дата:([^"]*)"\)/\1\/\/ Text("Дата:\2")/g' "$FILE"
fi

# 2) Если дата рисуется не Text("Дата:"), а через общий компонент (например DateHeader(...))
# то мы попробуем отключить её через флаг showDate (если есть) — иначе просто покажем подсказку.
if grep -qE 'DateHeader\(|Header\(|TopBar\(' "$FILE"; then
  echo "==> Found header call(s). Showing them:"
  grep -nE 'DateHeader\(|Header\(|TopBar\(' "$FILE" || true
  echo "==> If this header supports showDate/showDateChip, set it to false manually."
fi

echo "==> Git status"
git status --porcelain

echo "==> Commit + push"
git add "$FILE"
git commit -m "UI: hide date in article editor screen" || echo "No changes to commit."
git push
echo "DONE"
