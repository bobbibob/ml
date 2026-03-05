#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE" || { echo "ERROR: $FILE not found"; exit 1; }

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = p.read_text(encoding="utf-8")

# 1) Replace inaccessible colors
txt = txt.replace("TextBlack", "Color.Black")
txt = txt.replace("TextGray", "Color.Gray")

# 2) Ensure needed imports exist
def ensure_import(src: str, imp: str) -> str:
    if re.search(r'^\s*import\s+' + re.escape(imp) + r'\s*$', src, flags=re.M):
        return src
    # insert into import block
    m = re.search(r'^(package[^\n]*\n)(\s*import[^\n]*\n)+', src, flags=re.M)
    if m:
        end = m.end()
        return src[:end] + f"import {imp}\n" + src[end:]
    # fallback: after package
    m2 = re.search(r'^(package[^\n]*\n)', src, flags=re.M)
    if m2:
        end = m2.end()
        return src[:end] + f"\nimport {imp}\n" + src[end:]
    return src

for imp in [
    "androidx.compose.ui.Alignment",
    "androidx.compose.ui.graphics.Color",
    "androidx.compose.ui.text.style.TextOverflow",
]:
    txt = ensure_import(txt, imp)

p.write_text(txt, encoding="utf-8")
print("OK: patched AddEditArticleScreen.kt (Color.Black/Gray + imports Alignment/TextOverflow)")
PY

echo "DONE. Now:"
echo "  git add $FILE"
echo "  git commit -m \"Fix build: AddEditArticleScreen imports/colors\""
echo "  git push"
