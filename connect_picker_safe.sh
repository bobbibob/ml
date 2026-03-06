#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

[ -f "$FILE" ] || { echo "Не найден $FILE"; exit 1; }

cp "$FILE" "$FILE.bak_safe_picker"

# 1) локальный state для picker
sed -i '49i\
  var showArticlePicker by remember { mutableStateOf(false) }\
' "$FILE"

# 2) BackHandler: сначала закрываем picker, потом уже текущую логику
sed -i '52,57c\
  BackHandler(enabled = showArticlePicker || (state.mode is ScreenMode.Details) || (state.mode is ScreenMode.ArticleEditor)) {\
    when {\
      showArticlePicker -> showArticlePicker = false\
      state.mode is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()\
      else -> vm.backToTimeline()\
    }\
  }' "$FILE"

# 3) дату скрываем и в picker, и в редакторе
sed -i '119s/.*/            if (!showArticlePicker && state.mode !is ScreenMode.ArticleEditor) {/' "$FILE"

# 4) подключаем picker вместо переписывания ScreenMode
sed -i '139,156c\
          if (showArticlePicker) {\
            ArticlePickerScreen(\
              bagIds = state.timeline.flatMap { day -> day.byBags.map { bag -> bag.bagId } }.distinct().sorted(),\
              onCreateNew = {\
                showArticlePicker = false\
                vm.openArticleEditor()\
              },\
              onPick = { pickedBagId ->\
                showArticlePicker = false\
                vm.openArticleEditor(pickedBagId)\
              },\
              onCancel = { showArticlePicker = false }\
            )\
          } else when (state.mode) {\
            is ScreenMode.Timeline -> TimelineList(\
              items = state.timeline,\
              cardTypes = state.cardTypes,\
              onOpen = { vm.openDetails(LocalDate.parse(it.date)) }\
            )\
\
            is ScreenMode.Details -> DetailsList(\
              rows = state.rows,\
              cardTypes = state.cardTypes\
            )\
\
            is ScreenMode.ArticleEditor -> AddEditArticleScreen(\
              bagId = (state.mode as ScreenMode.ArticleEditor).bagId,\
              onDone = { vm.backFromArticleEditor() }\
            )\
          }' "$FILE"

# 5) нижняя кнопка открывает picker
sed -i '165s/.*/        onArticleClick = { showArticlePicker = true },/' "$FILE"

echo "== Проверка =="
nl -ba "$FILE" | sed -n '46,60p'
echo
nl -ba "$FILE" | sed -n '118,170p'

git add "$FILE"
git commit -m "UI: connect ArticlePickerScreen safely via local state" || echo "Нет изменений для коммита"
git push
