#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE" || { echo "ERROR: $FILE not found"; exit 1; }

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = p.read_text(encoding="utf-8")

needle = 'Text("Загрузить фото"'
count = txt.count(needle)
if count <= 1:
    print(f"OK: only {count} upload button(s), nothing to do")
    raise SystemExit(0)

# Ищем блоки Button/OutlinedButton, внутри которых есть Text("Загрузить фото")
# Удаляем ВСЕ, кроме первого.
pattern = re.compile(
    r'\n\s*(?:Button|OutlinedButton)\s*\([^)]*\)\s*\{(?:[^{}]|\{[^}]*\})*?'
    r'Text\("Загрузить фото"[^)]*\)(?:[^{}]|\{[^}]*\})*?\}\s*',
    re.DOTALL
)

matches = list(pattern.finditer(txt))
if not matches:
    # fallback: удаляем строки, где встречается Text("Загрузить фото") кроме первой
    parts = txt.split('Text("Загрузить фото"')
    txt2 = parts[0] + 'Text("Загрузить фото"' + ''.join(parts[2:])  # выкинули второй "хвост"
    p.write_text(txt2, encoding="utf-8")
    print("Patched (fallback): removed duplicate by literal split")
    raise SystemExit(0)

# Оставляем первый матч, остальные вырезаем с конца, чтобы индексы не поехали
for m in reversed(matches[1:]):
    txt = txt[:m.start()] + "\n" + txt[m.end():]

p.write_text(txt, encoding="utf-8")
print(f"Patched: removed {len(matches)-1} duplicate upload photo button(s). Was {count}.")
PY

echo "DONE. Now run:"
echo "  grep -n 'Загрузить фото' -n $FILE"
echo "  git add $FILE"
echo "  git commit -m 'UI: remove duplicate Upload photo button'"
echo "  git push"
