#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak"

echo "== До изменения =="
grep -n 'Дата:' "$FILE" || true

# Удаляем только строку с Text("Дата: ...")
sed -i '/Text("Дата: .*selectedDate.*TextOverflow.Ellipsis)/d' "$FILE"

echo "== После изменения =="
grep -n 'Дата:' "$FILE" || true

git add "$FILE"
git commit -m "UI: remove date line from SummaryScreen" || echo "Нет изменений для коммита"
git push
