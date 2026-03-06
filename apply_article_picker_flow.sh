#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

if [ ! -f "$FILE" ]; then
  echo "Файл не найден: $FILE"
  exit 1
fi

cp "$FILE" "$FILE.bak_picker_flow"

# 1) Добавим runtime imports, если их нет
grep -q '^import androidx.compose.runtime.mutableStateOf$' "$FILE" || sed -i '/^import androidx.compose.runtime.getValue$/a\import androidx.compose.runtime.mutableStateOf' "$FILE"
grep -q '^import androidx.compose.runtime.remember$' "$FILE" || sed -i '/^import androidx.compose.runtime.mutableStateOf$/a\import androidx.compose.runtime.remember' "$FILE"
grep -q '^import androidx.compose.runtime.setValue$' "$FILE" || sed -i '/^import androidx.compose.runtime.remember$/a\import androidx.compose.runtime.setValue' "$FILE"

# 2) Добавим локальные состояния в SummaryScreen после collectAsState()
awk '
BEGIN { inserted=0 }
{
  print
  if (!inserted && $0 ~ /collectAsState/) {
    print "  var showArticlePicker by remember { mutableStateOf(false) }"
    print "  var localEditorBagId by remember { mutableStateOf<String?>(null) }"
    inserted=1
  }
}
' "$FILE" > "$FILE.tmp"
mv "$FILE.tmp" "$FILE"

# 3) Скрываем кнопку даты не только в штатном редакторе, но и в локальном picker/editor
sed -i 's/if (state.mode !is ScreenMode.ArticleEditor) {/if (!showArticlePicker && localEditorBagId == null && state.mode !is ScreenMode.ArticleEditor) {/' "$FILE"

# 4) Заменяем when(state.mode) на локальный picker/editor flow
awk '
BEGIN { in_when=0; replaced=0 }
{
  if (!replaced && $0 ~ /^[[:space:]]*when \(state\.mode\) \{$/) {
    print "          val pickerBags = state.timeline"
    print "            .flatMap { day -> day.byBags.map { bag -> bag.bagId } }"
    print "            .distinct()"
    print "            .sorted()"
    print ""
    print "          when {"
    print "            showArticlePicker -> ArticlePickerList("
    print "              bagIds = pickerBags,"
    print "              onCreateNew = {"
    print "                localEditorBagId = \"\""
    print "                showArticlePicker = false"
    print "              },"
    print "              onPick = { pickedBagId ->"
    print "                localEditorBagId = pickedBagId"
    print "                showArticlePicker = false"
    print "              },"
    print "              onCancel = { showArticlePicker = false }"
    print "            )"
    print ""
    print "            localEditorBagId != null -> AddEditArticleScreen("
    print "              bagId = localEditorBagId?.ifBlank { null },"
    print "              onDone = { localEditorBagId = null }"
    print "            )"
    print ""
    print "            else -> when (state.mode) {"
    print "              is ScreenMode.Timeline -> TimelineList("
    print "                items = state.timeline,"
    print "                cardTypes = state.cardTypes,"
    print "                onOpen = { vm.openDetails(LocalDate.parse(it.date)) }"
    print "              )"
    print ""
    print "              is ScreenMode.Details -> DetailsList("
    print "                rows = state.rows,"
    print "                cardTypes = state.cardTypes"
    print "              )"
    print ""
    print "              is ScreenMode.ArticleEditor -> AddEditArticleScreen("
    print "                bagId = (state.mode as ScreenMode.ArticleEditor).bagId,"
    print "                onDone = { vm.backFromArticleEditor() }"
    print "              )"
    print "            }"
    print "          }"
    in_when=1
    replaced=1
    next
  }

  if (in_when) {
    if ($0 ~ /^[[:space:]]*}[[:space:]]*$/) {
      in_when=0
    }
    next
  }

  print
}
' "$FILE" > "$FILE.tmp"
mv "$FILE.tmp" "$FILE"

# 5) Кнопка снизу теперь открывает picker, а не сразу пустой editor
sed -i 's/onArticleClick = { vm.openArticleEditor() }/onArticleClick = { showArticlePicker = true }/' "$FILE"

# 6) Добавим новый composable ArticlePickerList, если его ещё нет
if ! grep -q 'private fun ArticlePickerList' "$FILE"; then
cat >> "$FILE" <<'EOF'

@Composable
private fun ArticlePickerList(
  bagIds: List<String>,
  onCreateNew: () -> Unit,
  onPick: (String) -> Unit,
  onCancel: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text(
      text = "Выберите артикул",
      style = MaterialTheme.typography.titleLarge,
      color = TextBlack
    )

    Button(
      onClick = onCreateNew,
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Новый артикул")
    }

    if (bagIds.isEmpty()) {
      Text("Пока нет артикулов", color = Color.Gray)
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(bagIds) { bagId ->
          Card(
            colors = CardDefaults.cardColors(containerColor = SoftGray),
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPick(bagId) }
          ) {
            Text(
              text = bagId,
              modifier = Modifier.padding(14.dp),
              color = TextBlack
            )
          }
        }
      }
    }

    Button(
      onClick = onCancel,
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Назад")
    }
  }
}
EOF
fi

echo "== Проверка ключевых мест =="
grep -n 'showArticlePicker' "$FILE" || true
grep -n 'localEditorBagId' "$FILE" || true
grep -n 'ArticlePickerList' "$FILE" || true
grep -n 'onArticleClick' "$FILE" || true

git add "$FILE"
git commit -m "UI: add article picker flow for new/edit entry" || echo "Нет изменений для коммита"
git push
