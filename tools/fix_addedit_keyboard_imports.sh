#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = p.read_text(encoding="utf-8").splitlines()

# Split into package, imports, and body
pkg_idx = None
for i, line in enumerate(txt):
    if line.strip().startswith("package "):
        pkg_idx = i
        break
if pkg_idx is None:
    raise SystemExit("No package line found")

package_line = txt[pkg_idx]

i = pkg_idx + 1
imports = []
body = []

# collect imports (and blank lines right after package/imports)
while i < len(txt):
    line = txt[i]
    if line.strip().startswith("import "):
        imports.append(line)
        i += 1
        continue
    # allow blank lines between imports
    if line.strip() == "" and (i+1 < len(txt) and txt[i+1].strip().startswith("import ")):
        imports.append(line)
        i += 1
        continue
    break

body = txt[i:]

# Clean broken/duplicated imports that may contain injected 'androidx...androidx...'
clean = []
seen = set()
for line in imports:
    s = line.strip()
    if not s.startswith("import "):
        # keep blank lines (we'll reformat later)
        continue
    if ".androidx." in s or "KeyboardOptions(" in s or "KeyboardType(" in s:
        # definitely broken import lines
        continue
    if s in seen:
        continue
    seen.add(s)
    clean.append(s)

# Ensure required imports exist
need = [
    "import androidx.compose.foundation.text.KeyboardOptions",
    "import androidx.compose.ui.text.input.KeyboardType",
]
for n in need:
    if n not in clean:
        clean.append(n)

# Sort imports a bit (optional but keeps stable)
clean = sorted(clean)

# Fix fully-qualified usages in body back to short names
body_txt = "\n".join(body)
body_txt = body_txt.replace("androidx.compose.ui.text.input.KeyboardOptions", "KeyboardOptions")
body_txt = body_txt.replace("androidx.compose.foundation.text.KeyboardOptions", "KeyboardOptions")
body_txt = body_txt.replace("androidx.compose.ui.text.input.KeyboardType", "KeyboardType")
# If there are any weird doubled qualifiers from previous replace:
body_txt = body_txt.replace("androidx.compose.ui.text.input.androidx.compose.ui.text.input.KeyboardType", "KeyboardType")
body_txt = body_txt.replace("androidx.compose.ui.text.input.androidx.compose.ui.text.input.KeyboardOptions", "KeyboardOptions")

out = []
out.append(package_line)
out.append("")
out.extend(clean)
out.append("")
out.append(body_txt.rstrip() + "\n")

p.write_text("\n".join(out), encoding="utf-8")
print("Patched imports + KeyboardOptions/KeyboardType in AddEditArticleScreen.kt")
PY

echo "DONE. Commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"Fix: KeyboardOptions imports\""
echo "  git push"
