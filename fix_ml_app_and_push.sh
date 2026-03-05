#!/usr/bin/env bash
set -euo pipefail

echo "=== ML AUTO FIX + PUSH ==="

# 0) Проверим что мы в git-репо
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "Не похоже на git-репозиторий"; exit 1; }

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "Текущая ветка: $BRANCH"

# 1) Найдём AddEditArticleScreen.kt
FILE="$(find app -name 'AddEditArticleScreen.kt' -print -quit || true)"
if [[ -z "${FILE}" ]]; then
  echo "Файл AddEditArticleScreen.kt не найден"
  exit 1
fi
echo "Найден: $FILE"

# 2) Бэкап
cp "$FILE" "$FILE.bak"

# 3) Удаляем строку с датой (внутри Add/Edit экрана)
# Удаляем любые строки, где встречается "Дата:" или "Дата :"
sed -i '/Дата[[:space:]]*:/d' "$FILE"

# 4) Ставим versionName = 1.0.0-alpha1
if [[ -f app/build.gradle.kts ]]; then
  sed -i 's/versionName[[:space:]]*=[[:space:]]*".*"/versionName = "1.0.0-alpha1"/' app/build.gradle.kts || true
fi
if [[ -f app/build.gradle ]]; then
  sed -i 's/versionName[[:space:]]*".*"/versionName "1.0.0-alpha1"/' app/build.gradle || true
fi

# 5) Commit (если есть изменения)
git add "$FILE" app/build.gradle.kts app/build.gradle 2>/dev/null || true
if git diff --cached --quiet; then
  echo "Нет изменений для коммита."
else
  git commit -m "UI: hide date in article editor + versionName 1.0.0-alpha1"
fi

# 6) Push (вот это и запускает сборку)
echo "Пушим в origin/$BRANCH ..."
git push -u origin "$BRANCH"

echo "DONE: Push выполнен. GitHub Actions должен стартовать."
