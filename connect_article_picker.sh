#!/usr/bin/env bash
set -euo pipefail

VM="app/src/main/java/com/ml/app/ui/SummaryViewModel.kt"
SCREEN="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

[ -f "$VM" ] || { echo "Не найден $VM"; exit 1; }
[ -f "$SCREEN" ] || { echo "Не найден $SCREEN"; exit 1; }

cp "$VM" "$VM.bak_picker_connect"
cp "$SCREEN" "$SCREEN.bak_picker_connect"

perl -0pi -e 's/data class Details\(val date: LocalDate\) : ScreenMode\(\)\s*\n\s*data class ArticleEditor/data class Details(val date: LocalDate) : ScreenMode()\n  data object ArticlePicker : ScreenMode()\n  data class ArticleEditor/s' "$VM"

perl -0pi -e 's/fun openArticleEditor\(bagId: String\? = null\) \{\s*_state\.value = _state\.value\.copy\(mode = ScreenMode\.ArticleEditor\(bagId\)\)\s*\}\s*\n\s*fun backFromArticleEditor\(\) \{\s*_state\.value = _state\.value\.copy\(mode = ScreenMode\.Timeline\)\s*\}/fun openArticlePicker() {\n    _state.value = _state.value.copy(mode = ScreenMode.ArticlePicker)\n  }\n\n  fun backFromArticlePicker() {\n    _state.value = _state.value.copy(mode = ScreenMode.Timeline)\n  }\n\n  fun openArticleEditor(bagId: String? = null) {\n    _state.value = _state.value.copy(mode = ScreenMode.ArticleEditor(bagId))\n  }\n\n  fun backFromArticleEditor() {\n    _state.value = _state.value.copy(mode = ScreenMode.Timeline)\n  }/s' "$VM"

perl -0pi -e 's/when \(state\.value\.mode\) \{\s*is ScreenMode\.Timeline -> refreshTimeline\(\)\s*is ScreenMode\.Details -> refreshDetails\(\)\s*is ScreenMode\.ArticleEditor -> \{ \/\* stay \*\/ \}\s*\}/when (state.value.mode) {\n        is ScreenMode.Timeline -> refreshTimeline()\n        is ScreenMode.Details -> refreshDetails()\n        is ScreenMode.ArticlePicker -> { /* stay */ }\n        is ScreenMode.ArticleEditor -> { /* stay */ }\n      }/s' "$VM"

perl -0pi -e 's/BackHandler\(enabled = \(state\.mode is ScreenMode\.Details\) \|\| \(state\.mode is ScreenMode\.ArticleEditor\)\) \{/BackHandler(enabled = (state.mode is ScreenMode.Details) || (state.mode is ScreenMode.ArticlePicker) || (state.mode is ScreenMode.ArticleEditor)) {/s' "$SCREEN"

perl -0pi -e 's/when \(state\.mode\) \{\s*is ScreenMode\.ArticleEditor -> vm\.backFromArticleEditor\(\)\s*else -> vm\.backToTimeline\(\)\s*\}/when (state.mode) {\n        is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()\n        is ScreenMode.ArticlePicker -> vm.backFromArticlePicker()\n        else -> vm.backToTimeline()\n      }/s' "$SCREEN"

perl -0pi -e 's/if \(state\.mode !is ScreenMode\.ArticleEditor\) \{/if (state.mode !is ScreenMode.ArticleEditor && state.mode !is ScreenMode.ArticlePicker) {/s' "$SCREEN"

perl -0pi -e 's/is ScreenMode\.Details -> DetailsList\(\s*rows = state\.rows,\s*cardTypes = state\.cardTypes\s*\)\s*\n\s*\n\s*\n\s*is ScreenMode\.ArticleEditor -> AddEditArticleScreen\(/is ScreenMode.Details -> DetailsList(\n              rows = state.rows,\n              cardTypes = state.cardTypes\n            )\n\n            is ScreenMode.ArticlePicker -> ArticlePickerScreen(\n              bagIds = state.timeline.flatMap { day -> day.byBags.map { bag -> bag.bagId } }.distinct().sorted(),\n              onCreateNew = { vm.openArticleEditor() },\n              onPick = { pickedBagId -> vm.openArticleEditor(pickedBagId) },\n              onCancel = { vm.backFromArticlePicker() }\n            )\n\n            is ScreenMode.ArticleEditor -> AddEditArticleScreen(/s' "$SCREEN"

perl -0pi -e 's/onArticleClick = \{ vm\.openArticleEditor\(\) \}/onArticleClick = { vm.openArticlePicker() }/s' "$SCREEN"

echo "== Проверка =="
grep -n "ArticlePicker" "$VM" || true
grep -n "ArticlePicker" "$SCREEN" || true
grep -n "onArticleClick" "$SCREEN" || true

git add "$VM" "$SCREEN"
git commit -m "UI: connect article picker flow safely" || echo "Нет изменений для коммита"
git push
