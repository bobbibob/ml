#!/usr/bin/env bash
set -e

echo "== Проверка ветки =="
git branch --show-current

echo "== Удаляем отображение даты =="
FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

if [ -f "$FILE" ]; then
  sed -i '/Дата:/d' "$FILE"
  echo "OK: строка даты удалена"
else
  echo "Файл не найден: $FILE"
fi

echo "== Добавляем состояние редактирования =="

grep -q "editingArticleId" "$FILE" || sed -i '/remember {/a\    var editingArticleId by remember { mutableStateOf<Long?>(null) }' "$FILE"

echo "== Меняем текст кнопки =="

sed -i 's/Добавить\/редактировать артикул/Добавить артикул/g' "$FILE"

echo "== Коммит =="
git add -A
git commit -m "Article editor: remove date + prepare edit mode" || echo "Нет изменений"

echo "== Push =="
git push

echo ""
echo "DONE: отправлено на GitHub Actions"
