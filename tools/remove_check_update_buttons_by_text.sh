#!/usr/bin/env bash
set -euo pipefail

BRANCH="stable_build"
git fetch --all
git checkout "$BRANCH"

echo "== Find RU labels in Kotlin =="
grep -R --line-number --include="*.kt" -E '"Проверить"|"Обновить"' app/src/main/java || true

echo "== Find RU labels in strings.xml =="
STRINGS="app/src/main/res/values/strings.xml"
if [ -f "$STRINGS" ]; then
  grep -n -E '>Проверить<|>Обновить<' "$STRINGS" || true
fi

python3 - <<'PY'
from pathlib import Path
import re

root_java = Path("app/src/main/java")
strings_path = Path("app/src/main/res/values/strings.xml")

# 1) достаём имена string ресурсов, где value == "Проверить"/"Обновить"
name_by_value = {}
if strings_path.exists():
    xml = strings_path.read_text(encoding="utf-8", errors="ignore")
    for m in re.finditer(r'<string\s+name="([^"]+)">\s*([^<]+?)\s*</string>', xml):
        name, val = m.group(1), m.group(2).strip()
        if val in ("Проверить", "Обновить"):
            name_by_value[val] = name

# шаблоны поиска "текста" внутри Button/IconButton
direct_text_patterns = [
    r'"Проверить"', r'"Обновить"'
]

# шаблоны stringResource(R.string.xxx)
res_patterns = []
for val, nm in name_by_value.items():
    res_patterns.append(rf'stringResource\s*\(\s*R\.string\.{re.escape(nm)}\s*\)')
    res_patterns.append(rf'R\.string\.{re.escape(nm)}')

# универсальный “вырезатель” кнопок по условию: внутри блока встретился target
def remove_buttons_by_targets(src: str, targets_regex: str) -> str:
    out = src

    # Удаляем Button/TextButton/OutlinedButton/IconButton, если внутри встречается target
    # Регекс сделан “широко”, но он работает именно по наличию target внутри блока.
    btn = r'(?:Button|TextButton|OutlinedButton|IconButton)'
    pattern1 = rf'{btn}\s*\((?:[^()]|\([^()]*\))*?\)\s*\{{(?:(?:[^{{}}]|\{{[^{{}}]*\}}))*?{targets_regex}(?:(?:[^{{}}]|\{{[^{{}}]*\}}))*?\}}'
    out = re.sub(pattern1, "", out, flags=re.DOTALL)

    # Иногда content = {{ Text(...) }} внутри параметров, поэтому ещё один вариант:
    pattern2 = rf'{btn}\s*\((?:(?:[^()]|\([^()]*\))*?{targets_regex}(?:[^()]|\([^()]*\))*?)\)\s*\{{(?:(?:[^{{}}]|\{{[^{{}}]*\}}))*?\}}'
    out = re.sub(pattern2, "", out, flags=re.DOTALL)

    # TopAppBar actions: если там встречается target — обнуляем actions
    def patch_topbar(m):
        inside = m.group("inside")
        if re.search(targets_regex, inside, flags=re.DOTALL):
            inside2 = re.sub(r'actions\s*=\s*\{.*?\}\s*,?', 'actions = {} ,', inside, flags=re.DOTALL)
            inside2 = re.sub(r',\s*\)', ')', inside2)
            inside2 = re.sub(r'\{\s*\}\s*,\s*,', '{},', inside2)
            return f'{m.group(1)}({inside2})'
        return m.group(0)

    topbar = r'(TopAppBar|CenterAlignedTopAppBar|SmallTopAppBar|MediumTopAppBar|LargeTopAppBar)\s*\((?P<inside>.*?)\)'
    out = re.sub(topbar, patch_topbar, out, flags=re.DOTALL)

    # подчистка пустых строк
    out = re.sub(r'\n{3,}', '\n\n', out)
    return out

# targets: "Проверить"|"Обновить"|stringResource(R.string.xxx)
targets = direct_text_patterns + res_patterns
if targets:
    targets_regex = "(" + "|".join(targets) + ")"
else:
    targets_regex = r'("Проверить"|"Обновить")'

changed = []
for f in root_java.rglob("*.kt"):
    txt = f.read_text(encoding="utf-8", errors="ignore")
    new = remove_buttons_by_targets(txt, targets_regex)
    if new != txt:
        f.write_text(new, encoding="utf-8")
        changed.append(str(f))

print("removed buttons in files:", len(changed))
for c in changed:
    print(" *", c)

PY

echo "== Sanity: search again (should be empty or only comments) =="
grep -R --line-number --include="*.kt" -E '"Проверить"|"Обновить"' app/src/main/java || true

git add -A
if git diff --cached --quiet; then
  echo "Nothing changed (buttons not found by text/resources)."
  echo "Next: we must locate where they are drawn (shared header). Run:"
  echo '  grep -R --line-number --include="*.kt" -E "TopAppBar|AppBar|Header|Toolbar" app/src/main/java/com/ml/app/ui'
  exit 0
fi

git commit -m "UI: remove Check/Update buttons"
git push origin "$BRANCH"
echo "DONE: pushed to $BRANCH"
