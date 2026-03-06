#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak2"

awk '
BEGIN { in_btn=0; inserted=0 }
{
  if ($0 ~ /^[[:space:]]*\)[[:space:]]*\{$/ && prev ~ /modifier = Modifier\.weight\(1f\)/) {
    print $0
    print "              Text(\"Дата: ${state.selectedDate}\", color = TextBlack)"
    inserted=1
    in_btn=1
    next
  }

  print $0
  prev=$0
}
END {
  if (!inserted) {
    # noop
  }
}
' "$FILE" > "$FILE.tmp"

mv "$FILE.tmp" "$FILE"

echo "== Проверка =="
nl -ba "$FILE" | sed -n '120,136p'

git add "$FILE"
git commit -m "UI: restore date text in SummaryScreen button" || echo "Нет изменений для коммита"
git push
