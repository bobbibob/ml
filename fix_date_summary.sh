#!/usr/bin/env bash
set -e

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

echo "Fixing date visibility in $FILE"

sed -i 's/Text("Дата: ${state.selectedDate}"/if (!state.showArticleEditor) {\nText("Дата: ${state.selectedDate}"/' $FILE
sed -i 's/TextOverflow.Ellipsis)/TextOverflow.Ellipsis)\n}/' $FILE

git add $FILE
git commit -m "Hide date when article editor is open"
git push

echo "Done"
