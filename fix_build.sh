#!/usr/bin/env bash
set -e

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

echo "== Удаляем дубликаты editingArticleId =="

awk '!seen[$0]++' "$FILE" > "$FILE.tmp"
mv "$FILE.tmp" "$FILE"

echo "== Удаляем функции loadArticleForEdit =="

sed -i '/fun loadArticleForEdit/,/}/d' "$FILE"

echo "== Удаляем функции deleteArticleById =="

sed -i '/fun deleteArticleById/,/}/d' "$FILE"

echo "== Проверяем editingArticleId =="

COUNT=$(grep -c "editingArticleId" "$FILE" || true)

if [ "$COUNT" -eq 0 ]; then
  echo "Добавляем editingArticleId"
  sed -i '/remember {/a\
    var editingArticleId by remember { mutableStateOf<Long?>(null) }\
' "$FILE"
fi

echo "== Commit =="

git add -A
git commit -m "Fix: remove duplicated editingArticleId and restore build" || echo "нет изменений"

echo "== Push =="

git push

echo "Готово. GitHub Actions должна запустить сборку."
