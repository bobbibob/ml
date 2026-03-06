#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak_date_fix"

awk '
NR==118 {
  print "        } else {"
  print "          if (state.mode !is ScreenMode.ArticleEditor) {"
  print "            Row("
  print "              modifier = Modifier"
  print "                .fillMaxWidth()"
  print "                .padding(12.dp),"
  print "              horizontalArrangement = Arrangement.spacedBy(12.dp),"
  print "              verticalAlignment = Alignment.CenterVertically"
  print "            ) {"
  print "              Button("
  print "                onClick = { openDatePicker(state.selectedDate) { vm.setDateFromPicker(it) } },"
  print "                colors = ButtonDefaults.buttonColors(containerColor = SoftGray, contentColor = TextBlack),"
  print "                modifier = Modifier.weight(1f)"
  print "              ) {"
  print "                Text(\"Дата: ${state.selectedDate}\")"
  print "              }"
  print "            }"
  print "          }"
  next
}
NR>=118 && NR<=135 { next }
{ print }
' "$FILE" > "$FILE.tmp"

mv "$FILE.tmp" "$FILE"

echo "== Проверка куска =="
nl -ba "$FILE" | sed -n '118,140p'

git add "$FILE"
git commit -m "UI: restore date button and hide it in article editor" || echo "Нет изменений для коммита"
git push
