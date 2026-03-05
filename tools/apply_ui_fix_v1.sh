#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"
test -f "$FILE" || { echo "NOT FOUND: $FILE"; exit 1; }

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
txt = p.read_text(encoding="utf-8")

# 1) Удаляем кнопку "Обновить" рядом с датой (обычно это Button(... Text("Обновить") ...))
# Пытаемся аккуратно вырезать ближайший Button-блок, где внутри есть Text("Обновить")
pattern_btn = re.compile(
    r"""
    (?:\n[ \t]*)                # отступ
    (Button|FilledTonalButton|OutlinedButton)\s*\(  # Button(
    (?:
        (?!\n[ \t]*\1\s*\().     # не входим в вложенный такой же
        |\n
    )*?
    \)\s*\{                      # ) {
    (?:
        (?!\n[ \t]*\}).          # до закрытия блока
        |\n
    )*?
    Text\s*\(\s*"Обновить"       # Text("Обновить"
    (?:
        (?!\n[ \t]*\}).          # до закрытия блока
        |\n
    )*?
    \n[ \t]*\}                   # }
    """,
    re.VERBOSE
)

new_txt, n = pattern_btn.subn("\n", txt, count=1)
txt = new_txt
print(f"Removed 'Обновить' button blocks: {n}")

# 2) Скрываем кнопку/действие "Проверить" на экране артикула.
# Ищем Text("Проверить") и оборачиваем ближайший кусок в условие.
# Чтобы не гадать про точную структуру TopAppBar, делаем минимально:
# заменяем Text("Проверить") -> if (mode !is ScreenMode.ArticleEditor) Text("Проверить")
txt2, n2 = re.subn(
    r'Text\s*\(\s*"Проверить"\s*(,|\))',
    r'if (mode !is ScreenMode.ArticleEditor) Text("Проверить"\1',
    txt
)
txt = txt2
print(f"Wrapped 'Проверить' label: {n2}")

# если вставили if( ) без закрытия, добавим закрывающую } сразу после следующего ')'
# (это страховка на случай короткой формы Text("Проверить"))
if n2 > 0:
    # добавляем '}' после первой закрывающей скобки Text(...) в месте where we inserted
    # Найдем первое вхождение 'if (mode !is ScreenMode.ArticleEditor) Text("Проверить"' и закроем блок после ближайшей ')'
    idx = txt.find('if (mode !is ScreenMode.ArticleEditor) Text("Проверить"')
    if idx != -1:
        # Найдём ближайшее ')' после idx
        j = txt.find(')', idx)
        if j != -1:
            # если сразу после ')' нет '}', вставим
            after = txt[j+1:j+50]
            if '}' not in after:
                txt = txt[:j+1] + " }\n" + txt[j+1:]

p.write_text(txt, encoding="utf-8")
print("OK: patched SummaryScreen.kt (no /tmp used)")
PY

echo "DONE: UI buttons patched."
