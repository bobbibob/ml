#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text()

old = '''    LaunchedEffect(selectedBagId) {
        val id = selectedBagId ?: return@LaunchedEffect
        loadBagFromPicker(id)
    }
'''

new = '''    LaunchedEffect(selectedBagId) {
        val id = selectedBagId ?: return@LaunchedEffect

        loadBagFromPicker(id)

        val seed = kotlin.runCatching { repo.getBagEditorSeed(id) }.getOrNull()
        if (seed != null) {
            if (name.isBlank()) name = seed.bagName
            if (hypothesis.isBlank()) hypothesis = seed.hypothesis.orEmpty()
            if (priceAll.isBlank()) priceAll = seed.price?.toString().orEmpty()
            if (cost.isBlank()) cost = seed.cogs?.toString().orEmpty()

            colorDrafts.clear()
            colorDrafts.addAll(seed.colors.distinct().map { color ->
                ColorDraft(
                    color = color,
                    priceText = if (priceForAllEnabled) "" else priceAll
                )
            })
        }
    }
'''

if old not in s:
    raise SystemExit("selectedBagId effect not found")

s = s.replace(old, new, 1)
p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "restore colors from bag editor seed" || true
git push
