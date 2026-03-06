#!/usr/bin/env bash
set -e

python3 <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text()

decl = "var photoPath by remember { mutableStateOf<String?>(null) }"

# удалить старое объявление
s = s.replace("    " + decl + "\n", "")

# вставить перед imagePicker
marker = "val imagePicker = rememberLauncherForActivityResult("
s = s.replace(marker, "var photoPath by remember { mutableStateOf<String?>(null) }\n\n    " + marker)

p.write_text(s)
print("fixed")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "fix photoPath order"
git push
