#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = p.read_text(encoding="utf-8")

# Replace short names with fully-qualified to avoid import issues
txt2 = txt.replace("KeyboardOptions(", "androidx.compose.ui.text.input.KeyboardOptions(")
txt2 = txt2.replace("KeyboardType.", "androidx.compose.ui.text.input.KeyboardType.")

if txt2 == txt:
    print("No changes: nothing to replace.")
else:
    p.write_text(txt2, encoding="utf-8")
    print("Patched KeyboardOptions/KeyboardType references.")
PY

echo "DONE. Now commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"Fix: fully-qualify KeyboardOptions in AddEditArticleScreen\""
echo "  git push"
