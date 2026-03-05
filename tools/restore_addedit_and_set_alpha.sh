#!/usr/bin/env bash
set -euo pipefail

GOOD_COMMIT="e490ca6"
FILE_UI="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
FILE_GRADLE="app/build.gradle.kts"

echo "1) Restore $FILE_UI from $GOOD_COMMIT"
git checkout "$GOOD_COMMIT" -- "$FILE_UI"

echo "2) Set versionName to 1.0 Альфа in $FILE_GRADLE"
python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")

# заменить versionName = "..." на versionName = "1.0 Альфа"
s2, n = re.subn(r'versionName\s*=\s*"[^\"]*"', 'versionName = "1.0 Альфа"', s, count=1)
if n == 0:
    raise SystemExit("ERROR: versionName not found in app/build.gradle.kts")

p.write_text(s2, encoding="utf-8")
print("OK: versionName updated")
PY

echo "DONE. Now commit & push:"
echo "  git add $FILE_UI $FILE_GRADLE"
echo "  git commit -m \"Restore AddEditArticleScreen and set version 1.0 Альфа\""
echo "  git push"
