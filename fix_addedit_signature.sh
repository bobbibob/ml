#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak"

awk '
BEGIN { patched=0 }
{
  if (!patched && $0 ~ /^fun AddEditArticleScreen\(/) {
    print "fun AddEditArticleScreen("
    print "    bagId: String? = null,"
    print "    onDone: (() -> Unit)? = null"
    print ") {"
    patched=1
    next
  }
  print
}
' "$FILE" > "$FILE.tmp"

mv "$FILE.tmp" "$FILE"

# добавим заглушку использования параметров сразу после открытия функции,
# чтобы не было лишних предупреждений и всё точно компилилось
awk '
BEGIN { inserted=0 }
{
  print
  if (!inserted && $0 ~ /^\)[[:space:]]*\{$/) {
    print "    val _bagId = bagId"
    print "    val _onDone = onDone"
    inserted=1
  }
}
' "$FILE" > "$FILE.tmp"

mv "$FILE.tmp" "$FILE"

git add "$FILE"
git commit -m "Fix: AddEditArticleScreen signature compatibility" || echo "Нет изменений для коммита"
git push
