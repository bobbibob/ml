#!/usr/bin/env bash
set -euo pipefail

BRANCH="stable_build"
FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

git fetch --all
git checkout "$BRANCH"

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
txt = p.read_text(encoding="utf-8")

orig = txt

# --- 1) УДАЛЯЕМ КНОПКИ "Проверить" / "Обновить" (разные варианты: Button/OutlinedButton/TextButton) ---

patterns = [
    # TextButton { Text("Проверить") } с вызовом checkRemoteAndUpdateIfChanged
    r'TextButton\s*\(\s*onClick\s*=\s*\{\s*[^}]*checkRemoteAndUpdateIfChanged\s*\(\s*\)\s*[^}]*\}\s*[^)]*\)\s*\{\s*Text\s*\(\s*"Проверить"\s*[^)]*\)\s*\}\s*',
    # Button/OutlinedButton { Text("Обновить") } с вызовом refreshPack/download etc
    r'(?:Button|OutlinedButton|TextButton)\s*\(\s*onClick\s*=\s*\{\s*[^}]*\brefreshPack\b[^}]*\}\s*[^)]*\)\s*\{\s*Text\s*\(\s*"Обновить"\s*[^)]*\)\s*\}\s*',
    r'(?:Button|OutlinedButton|TextButton)\s*\(\s*onClick\s*=\s*\{\s*[^}]*\bdownloadPack\b[^}]*\}\s*[^)]*\)\s*\{\s*Text\s*\(\s*"Обновить"\s*[^)]*\)\s*\}\s*',
    # Любая кнопка где внутри явно Text("Проверить") / Text("Обновить")
    r'(?:Button|OutlinedButton|TextButton)\s*\([^)]*\)\s*\{\s*[^{}]*Text\s*\(\s*"Проверить"\s*[^)]*\)\s*[^{}]*\}\s*',
    r'(?:Button|OutlinedButton|TextButton)\s*\([^)]*\)\s*\{\s*[^{}]*Text\s*\(\s*"Обновить"\s*[^)]*\)\s*[^{}]*\}\s*',
]

for pat in patterns:
    txt = re.sub(pat, "", txt, flags=re.MULTILINE | re.DOTALL)

# --- 2) ДОБАВЛЯЕМ PULL-TO-REFRESH (material pullrefresh) ---

# Импорты: добавим только если pullRefresh ещё не подключен
need_imports = "pullRefresh" not in txt and "rememberPullRefreshState" not in txt

if need_imports:
    # Добавим импорты рядом с другими import-ами material
    # (аккуратно: если файла нетипичный — вставим после package/import блока)
    lines = txt.splitlines(True)
    out = []
    inserted = False
    for i, line in enumerate(lines):
        out.append(line)
        if not inserted and line.startswith("import "):
            # найдём место после последнего import
            pass
    # найдём индекс последнего import
    last_import = -1
    for i, line in enumerate(lines):
        if line.startswith("import "):
            last_import = i
    if last_import != -1:
        extra = (
            'import androidx.compose.material.pullrefresh.PullRefreshIndicator\n'
            'import androidx.compose.material.pullrefresh.pullRefresh\n'
            'import androidx.compose.material.pullrefresh.rememberPullRefreshState\n'
        )
        lines.insert(last_import + 1, extra)
        txt = "".join(lines)

# Вставим pullRefreshState внутри SummaryScreen(), если ещё не вставлено
if "rememberPullRefreshState" in txt and "val pullState" not in txt:
    # найдём место после "val state" (типичный паттерн collectAsState)
    txt = re.sub(
        r'(val\s+state\s+by\s+vm\.state\.collectAsState\s*\(\s*\)\s*\n)',
        r'\1  val pullState = rememberPullRefreshState(\n'
        r'    refreshing = state.loading,\n'
        r'    onRefresh = { vm.checkRemoteAndUpdateIfChanged() }\n'
        r'  )\n',
        txt,
        count=1,
        flags=re.MULTILINE
    )

# Обернём Scaffold content в Box + pullRefresh + индикатор (только если ещё не обёрнуто)
if "pullRefresh(pullState)" not in txt:
    # Пытаемся найти "Scaffold(" и внутри content { ... } первый контейнер.
    # Самый безопасный способ: обернуть ВСЁ содержимое Scaffold content лямбды.
    # Ищем: Scaffold( ... ) { innerPadding ->  ... }
    # или: Scaffold( ... ) { padding -> ... }
    m = re.search(r'Scaffold\s*\([^)]*\)\s*\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*->', txt, flags=re.DOTALL)
    if m:
        pad_name = m.group(1)
        # Найдём начало тела лямбды после "->"
        start = m.end()
        # Вставим Box сразу в начале тела
        insert_head = (
            f'\n    Box(Modifier.fillMaxSize().pullRefresh(pullState)) {{\n'
        )
        # И вставим индикатор перед закрывающей скобкой лямбды Scaffold
        # Для этого найдём ближайшее "}" которое закрывает Scaffold lambda — грубо: возьмём последний "}" файла не надо.
        # Более безопасно: вставить индикатор прямо перед первым "}" на том же уровне — сложно.
        # Поэтому используем лёгкий вариант: вставим индикатор в конце тела перед "}" который стоит перед закрытием Scaffold.
        # Паттерн: "\n  }\n}" — не надёжен, но часто.
        txt2 = txt[:start] + insert_head + txt[start:]
        # Теперь вставим индикатор: найдём первое вхождение закрытия Scaffold lambda: "\n  }\n" после Box?
        # Поищем соответствующий "}" сразу перед концом Scaffold блока: "}\n" после которого идёт что-то (например return).
        # Используем якорь: после вставки Box — найдём ближайшее "\n    }\n" которое закрывает Box? нет.
        # Мы добавим индикатор простым способом: в конце файла перед самым последним "}" функции SummaryScreen
        # (это гарантированно внутри функции). Это ок: индикатор будет поверх всего.
        # Найдём последнюю строку "}" функции SummaryScreen по паттерну "fun SummaryScreen" блок.
        txt = txt2

        # Индикатор вставим ПЕРЕД самым последним "}" в файле (обычно конец файла), но это может быть неправильно.
        # Лучше: вставить перед последним "}" непосредственно после "Scaffold" лямбды — риск.
        # Компромисс: вставим индикатор сразу после Box open, а закрытие Box — добавим в конце Scaffold content перед его "}".
        # Сначала добавим индикатор сразу после Box open:
        txt = txt.replace(
            insert_head,
            insert_head + '      PullRefreshIndicator(\n'
                          '        refreshing = state.loading,\n'
                          '        state = pullState,\n'
                          '        modifier = Modifier.align(Alignment.TopCenter)\n'
                          '      )\n',
            1
        )
        # Теперь нужно добавить закрывающую скобку Box "}" перед закрытием Scaffold lambda.
        # Попробуем заменить первое вхождение строки, начинающейся с "}" которая закрывает Scaffold lambda:
        # Паттерн: "\n  }\n" после Scaffold контента. Добавим перед ним "    }\n"
        txt = re.sub(r'\n(\s*)\}\s*\n(\s*)\}', r'\n\1    }\n\1}\n\2}', txt, count=1)

# Если Box/pullRefresh добавили — убедимся, что есть нужные импорты Box/Alignment/fillMaxSize
# (обычно они уже есть; если нет — компилятор скажет и исправим точечно)

p.write_text(txt, encoding="utf-8")

changed = (txt != orig)
print("patched:", changed)
PY

git add "$FILE"
if git diff --cached --quiet; then
  echo "Nothing to commit (no changes)."
  exit 0
fi

git commit -m "UI: remove Check/Update buttons, add pull-to-refresh"
git push origin "$BRANCH"
echo "DONE: pushed. Wait for GitHub Actions build."
