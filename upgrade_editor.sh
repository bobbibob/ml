#!/usr/bin/env bash
set -e

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

echo "== Проверка файла =="

if [ ! -f "$FILE" ]; then
  echo "Файл не найден:"
  echo "$FILE"
  exit 1
fi

echo "== Удаляем отображение даты =="

sed -i '/Дата:/d' "$FILE" || true

echo "== Добавляем состояние редактирования =="

grep -q "editingArticleId" "$FILE" || sed -i '/remember {/a\
    var editingArticleId by remember { mutableStateOf<Long?>(null) }\
' "$FILE"

echo "== Меняем текст кнопки =="

sed -i 's/Добавить\/редактировать артикул/Добавить артикул/g' "$FILE" || true

echo "== Добавляем обработку редактирования =="

grep -q "loadArticleForEdit" "$FILE" || cat << 'BLOCK' >> "$FILE"

fun loadArticleForEdit(
    article: Article,
    onLoad: (String, String, String) -> Unit
) {
    onLoad(article.name, article.description, article.photoUri ?: "")
}
BLOCK

echo "== Добавляем функцию удаления =="

grep -q "deleteArticleById" "$FILE" || cat << 'BLOCK' >> "$FILE"

fun deleteArticleById(
    id: Long,
    repository: ArticleRepository
) {
    repository.deleteById(id)
}
BLOCK

echo "== Git commit =="

git add -A
git commit -m "Upgrade: article editor + delete + edit support" || echo "Нет изменений"

echo "== Git push =="

git push

echo ""
echo "Готово. GitHub Actions должна начать сборку."
