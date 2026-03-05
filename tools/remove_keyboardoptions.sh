#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8").splitlines()

out = []
for line in s:
    # убираем импорты KeyboardOptions/KeyboardType если они есть
    if "foundation.text.KeyboardOptions" in line:
        continue
    if "text.input.KeyboardType" in line:
        continue

    # убираем параметры keyboardOptions и любые хвосты с KeyboardOptions/KeyboardType
    if "keyboardOptions" in line:
        continue
    if "KeyboardOptions" in line:
        continue
    if "KeyboardType" in line:
        continue

    out.append(line)

p.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")
print("Patched: removed keyboardOptions/KeyboardOptions/KeyboardType from AddEditArticleScreen.kt")
PY

echo "DONE. Commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"Fix build: remove KeyboardOptions usage\""
echo "  git push"
