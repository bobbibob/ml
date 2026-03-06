#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak"

echo "== Ищем выражение bagId = ... в SummaryScreen"
BAG_EXPR="$(grep -n 'bagId[[:space:]]*=' "$FILE" | head -n 1 | sed -E 's/.*bagId[[:space:]]*=[[:space:]]*([^,)]*).*/\1/' | xargs)"

if [ -z "${BAG_EXPR:-}" ]; then
  echo "Не удалось найти bagId = ... в $FILE"
  exit 1
fi

echo "Найдено выражение bagId: $BAG_EXPR"

DATE_LINE='Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)'
REPLACEMENT="if (${BAG_EXPR} == null) Text(\"Дата: \${state.selectedDate}\", maxLines = 1, overflow = TextOverflow.Ellipsis)"

if grep -Fq "$DATE_LINE" "$FILE"; then
  sed -i "s|$DATE_LINE|$REPLACEMENT|g" "$FILE"
else
  echo "Точная строка даты не найдена. Показываю похожие:"
  grep -n 'Дата:' "$FILE" || true
  exit 1
fi

git add "$FILE"
git commit -m "UI: hide date when article editor is open" || echo "Нет изменений для коммита"
git push
