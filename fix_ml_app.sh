#!/usr/bin/env bash
set -e

echo "=== ML AUTO FIX SCRIPT ==="

echo "1. Поиск AddEditArticleScreen.kt"
FILE=$(find app -name "AddEditArticleScreen.kt" | head -n 1)

if [ -z "$FILE" ]; then
echo "Файл AddEditArticleScreen.kt не найден"
exit 1
fi

echo "Найден: $FILE"

echo "2. Бэкап файла"
cp "$FILE" "$FILE.bak"

echo "3. Удаляем строку с датой"
sed -i '/Дата:/d' "$FILE"

echo "4. Устанавливаем версию 1.0.0-alpha1"

if [ -f app/build.gradle.kts ]; then
sed -i 's/versionName *= "."/versionName = "1.0.0-alpha1"/' app/build.gradle.kts
fi

if [ -f app/build.gradle ]; then
sed -i 's/versionName "."/versionName "1.0.0-alpha1"/' app/build.gradle
fi

echo "5. Добавляем изменения в git"

git add .

git commit -m "UI fix: remove date from article editor + version 1.0.0-alpha1" || true

echo "6. Сборка проекта"

./gradlew :app:assembleDebug

echo "ГОТОВО"
