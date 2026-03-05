#!/usr/bin/env bash
set -euo pipefail

BRANCH="stable_build"
git fetch --all
git checkout "$BRANCH"

echo "== Where handlers are referenced (BEFORE) =="
grep -R --line-number --include="*.kt" -E "checkRemoteAndUpdateIfChanged|refreshPack\(" app/src/main/java/com/ml/app/ui || true

python3 - <<'PY'
from pathlib import Path
import re

root = Path("app/src/main/java/com/ml/app/ui")
files = list(root.rglob("*.kt"))

def strip_click_blocks(src: str) -> str:
    # Удаляем Button/TextButton/OutlinedButton/IconButton, если внутри onClick вызывается:
    # vm.checkRemoteAndUpdateIfChanged() или vm.refreshPack()
    # (регекс широкий, но в рамках UI это ок)
    patterns = [
        # Button(...) { ... vm.checkRemoteAndUpdateIfChanged() ... }
        r'(?:Button|TextButton|OutlinedButton|IconButton)\s*\((?:[^()]|\([^()]*\))*?\)\s*\{(?:[^{}]|\{[^{}]*\})*?vm\.(?:checkRemoteAndUpdateIfChanged|refreshPack)\s*\(\s*\)(?:[^{}]|\{[^{}]*\})*?\}',
        # Row/Box { ... Button(onClick={ vm... }) ... } — иногда onClick прямо внутри параметров
        r'(?:Button|TextButton|OutlinedButton|IconButton)\s*\((?:[^()]|\([^()]*\))*?onClick\s*=\s*\{[^}]*?vm\.(?:checkRemoteAndUpdateIfChanged|refreshPack)\s*\(\s*\)[^}]*?\}(?:[^()]|\([^()]*\))*?\)\s*\{(?:[^{}]|\{[^{}]*\})*?\}',
    ]
    out = src
    for p in patterns:
        out2 = re.sub(p, "", out, flags=re.DOTALL)
        out = out2

    # Убираем actions = { ...vm.checkRemoteAndUpdateIfChanged... } в TopAppBar* (оставляем пустые actions)
    out = re.sub(
        r'(TopAppBar|CenterAlignedTopAppBar|SmallTopAppBar|MediumTopAppBar|LargeTopAppBar)\s*\((?P<inside>.*?)\)',
        lambda m: _patch_topappbar(m),
        out,
        flags=re.DOTALL
    )

    # подчистка лишних пустых строк
    out = re.sub(r'\n{3,}', '\n\n', out)
    return out

def _patch_topappbar(m):
    inside = m.group("inside")
    # если внутри actions есть вызов checkRemoteAndUpdateIfChanged/refreshPack — заменим весь actions на пустой
    if re.search(r'actions\s*=\s*\{[^}]*vm\.(checkRemoteAndUpdateIfChanged|refreshPack)\s*\(', inside, flags=re.DOTALL):
        inside2 = re.sub(r'actions\s*=\s*\{.*?\}\s*,?', 'actions = {} ,', inside, flags=re.DOTALL)
        # если actions был последним и осталась лишняя запятая/пробелы — подчистим
        inside2 = re.sub(r',\s*\)', ')', inside2)
        inside2 = re.sub(r'\{\s*\}\s*,\s*,', '{},', inside2)
        return f'{m.group(1)}({inside2})'
    return m.group(0)

changed = []
for f in files:
    before = f.read_text(encoding="utf-8", errors="ignore")
    after = strip_click_blocks(before)
    if after != before:
        f.write_text(after, encoding="utf-8")
        changed.append(f)

print("changed:", len(changed))
for c in changed:
    print(" *", c)
PY

echo "== Where handlers are referenced (AFTER) =="
grep -R --line-number --include="*.kt" -E "checkRemoteAndUpdateIfChanged|refreshPack\(" app/src/main/java/com/ml/app/ui || true

git add -A
if git diff --cached --quiet; then
  echo "Nothing to commit (no changes)."
  exit 0
fi

git commit -m "UI: remove Check/Update buttons (use pull-to-refresh)"
git push origin "$BRANCH"

echo "DONE: pushed."
