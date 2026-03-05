#!/usr/bin/env bash
set -euo pipefail

GRADLE="app/build.gradle.kts"
FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

python3 - <<'PY'
from pathlib import Path
import re

# --- 1) ensure compose foundation dependency ---
p = Path("app/build.gradle.kts")
g = p.read_text(encoding="utf-8")

if 'androidx.compose.foundation:foundation' not in g:
    # try to insert after ui dependency line, else inside dependencies block
    lines = g.splitlines()
    out = []
    inserted = False
    in_deps = False

    for line in lines:
        out.append(line)
        if line.strip().startswith("dependencies"):
            in_deps = True

        if (not inserted) and in_deps and 'implementation("androidx.compose.ui:ui")' in line:
            out.append('  implementation("androidx.compose.foundation:foundation")')
            inserted = True

    if not inserted:
        # fallback: insert right after dependencies { line
        out2 = []
        for line in out:
            out2.append(line)
            if (not inserted) and re.match(r'^\s*dependencies\s*\{\s*$', line):
                out2.append('  implementation("androidx.compose.foundation:foundation")')
                inserted = True
        out = out2

    g2 = "\n".join(out) + "\n"
    p.write_text(g2, encoding="utf-8")
    print("Patched build.gradle.kts: added androidx.compose.foundation:foundation")
else:
    print("build.gradle.kts: foundation already present")

# --- 2) fix imports in AddEditArticleScreen.kt ---
f = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = f.read_text(encoding="utf-8").splitlines()

# find package line
pkg_i = None
for i, line in enumerate(txt):
    if line.strip().startswith("package "):
        pkg_i = i
        break
if pkg_i is None:
    raise SystemExit("No package line in AddEditArticleScreen.kt")

# collect existing imports
i = pkg_i + 1
imports = []
body_start = i
while body_start < len(txt) and (txt[body_start].strip() == "" or txt[body_start].strip().startswith("import ")):
    if txt[body_start].strip().startswith("import "):
        imports.append(txt[body_start].strip())
    body_start += 1

need = {
  "import androidx.compose.foundation.text.KeyboardOptions",
  "import androidx.compose.ui.text.input.KeyboardType",
}

for n in need:
    imports.append(n)

# de-dup + sort
imports = sorted(set(imports))

out = []
out.append(txt[pkg_i].rstrip())
out.append("")
out.extend(imports)
out.append("")
out.extend(txt[body_start:])

f.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")
print("Patched AddEditArticleScreen.kt: added KeyboardOptions/KeyboardType imports")
PY

echo "DONE. Now commit & push:"
echo "  git add $GRADLE $FILE"
echo "  git commit -m \"Fix: add compose foundation + KeyboardOptions imports\""
echo "  git push"
