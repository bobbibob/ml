#!/usr/bin/env bash
set -euo pipefail

BRANCH="stable_build"
ROOTS=("app/src/main/java" "app/src/main/res")

git fetch --all
git checkout "$BRANCH"

echo "== Find places where UI shows 'Проверить' / 'Обновить' =="
MATCHES=$(grep -R --line-number --include="*.kt" -E 'Text\("Проверить"|Text\("Обновить"|\"Проверить\"|\"Обновить\"' "${ROOTS[@]}" || true)
echo "$MATCHES" | sed '/^$/d' || true

python3 - <<'PY'
from pathlib import Path
import re

roots = [Path("app/src/main/java"), Path("app/src/main/res")]
files = []
for root in roots:
    if root.exists():
        files += list(root.rglob("*.kt"))

targets = []
for f in files:
    s = f.read_text(encoding="utf-8", errors="ignore")
    if ("Проверить" in s) or ("Обновить" in s):
        targets.append(f)

print(f"targets: {len(targets)}")
for t in targets:
    print(" -", t)

def drop_buttons(src: str) -> str:
    # Удаляем любые Button/TextButton/OutlinedButton блоки, внутри которых есть Text("Проверить"/"Обновить")
    # Захватываем и многострочные варианты.
    btn = r'(?:Button|TextButton|OutlinedButton)\s*\([^)]*\)\s*\{(?:[^{}]|\{[^{}]*\})*?Text\s*\(\s*"(?:Проверить|Обновить)"[^)]*\)(?:[^{}]|\{[^{}]*\})*?\}'
    src2 = re.sub(btn, "", src, flags=re.DOTALL)

    # Иногда это IconButton + Text в Row (реже), но на всякий случай:
    icon_btn = r'IconButton\s*\([^)]*\)\s*\{(?:[^{}]|\{[^{}]*\})*?Text\s*\(\s*"(?:Проверить|Обновить)"[^)]*\)(?:[^{}]|\{[^{}]*\})*?\}'
    src2 = re.sub(icon_btn, "", src2, flags=re.DOTALL)

    # Часто кнопки стоят рядом в Row(...) { ... } — после удаления может остаться ", ,", подчистим двойные переводы
    src2 = re.sub(r'\n{3,}', '\n\n', src2)
    return src2

changed_files = []

for f in targets:
    before = f.read_text(encoding="utf-8", errors="ignore")
    after = drop_buttons(before)
    if after != before:
        f.write_text(after, encoding="utf-8")
        changed_files.append(f)

# --- Pull-to-refresh: пробуем внедрить в SummaryScreen.kt (если он существует) ---
ss = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
if ss.exists():
    txt = ss.read_text(encoding="utf-8", errors="ignore")
    orig = txt

    # 1) Импорты pullrefresh (только если нет)
    if "rememberPullRefreshState" not in txt:
        # вставим после последнего import
        lines = txt.splitlines(True)
        last_import = max((i for i,l in enumerate(lines) if l.startswith("import ")), default=-1)
        if last_import != -1:
            extra = (
                "import androidx.compose.material.pullrefresh.PullRefreshIndicator\n"
                "import androidx.compose.material.pullrefresh.pullRefresh\n"
                "import androidx.compose.material.pullrefresh.rememberPullRefreshState\n"
            )
            lines.insert(last_import+1, extra)
            txt = "".join(lines)

    # 2) Создать pullState (ищем разные варианты state)
    if "val pullState" not in txt and "rememberPullRefreshState" in txt:
        patterns = [
            r'(val\s+state\s+by\s+vm\.state\.collectAsState\s*\(\s*\)\s*\n)',
            r'(val\s+state\s*=\s*vm\.state\.collectAsState\s*\(\s*\)\.value\s*\n)',
            r'(val\s+uiState\s+by\s+vm\.state\.collectAsState\s*\(\s*\)\s*\n)',
        ]
        inserted = False
        for pat in patterns:
            if re.search(pat, txt):
                txt = re.sub(
                    pat,
                    lambda m: m.group(0) + "  val pullState = rememberPullRefreshState(\n"
                                "    refreshing = (state.loading),\n"
                                "    onRefresh = { vm.checkRemoteAndUpdateIfChanged() }\n"
                                "  )\n",
                    txt,
                    count=1,
                    flags=re.MULTILINE
                )
                inserted = True
                break

        # если uiState вариант — поправим refreshing выражение
        if not inserted and "uiState" in txt:
            txt = re.sub(
                r'(val\s+uiState\s+by\s+vm\.state\.collectAsState\s*\(\s*\)\s*\n)',
                r'\1  val pullState = rememberPullRefreshState(\n'
                r'    refreshing = (uiState.loading),\n'
                r'    onRefresh = { vm.checkRemoteAndUpdateIfChanged() }\n'
                r'  )\n',
                txt,
                count=1,
                flags=re.MULTILINE
            )

    # 3) Обернуть контент в Box(...pullRefresh...) если ещё нет
    if "pullRefresh(pullState)" not in txt and "val pullState" in txt:
        # добавим Box сразу после начала Scaffold lambda
        m = re.search(r'Scaffold\s*\([^)]*\)\s*\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*->', txt, flags=re.DOTALL)
        if m:
            pad = m.group(1)
            start = m.end()
            head = f"\n    Box(Modifier.fillMaxSize().pullRefresh(pullState)) {{\n" \
                   f"      PullRefreshIndicator(\n" \
                   f"        refreshing = state.loading,\n" \
                   f"        state = pullState,\n" \
                   f"        modifier = Modifier.align(Alignment.TopCenter)\n" \
                   f"      )\n"
            txt = txt[:start] + head + txt[start:]

            # закрыть Box: вставим перед ближайшим "}" который закрывает Scaffold lambda.
            # Делается эвристикой: перед последней строкой "}" в функции SummaryScreen, если она есть.
            # Найдём конец функции SummaryScreen: последний "}" файла.
            idx = txt.rfind("}")
            if idx != -1:
                txt = txt[:idx] + "    }\n" + txt[idx:]

    if txt != orig:
        ss.write_text(txt, encoding="utf-8")
        if ss not in changed_files:
            changed_files.append(ss)

print("changed:", len(changed_files))
for f in changed_files:
    print(" *", f)
PY

git add -A

if git diff --cached --quiet; then
  echo "Nothing to commit (no changes)."
  exit 0
fi

git commit -m "UI: remove Check/Update buttons, add pull-to-refresh"
git push origin "$BRANCH"
echo "DONE: pushed."
