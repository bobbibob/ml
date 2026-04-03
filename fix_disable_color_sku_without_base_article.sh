#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
cp "$FILE" "$FILE.bak.disable_color_sku.$(date +%s)"

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8")

# 1) Добавить alpha import, если его нет
if 'import androidx.compose.ui.draw.alpha' not in s:
    s = s.replace(
        'import androidx.compose.ui.Alignment\n',
        'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.draw.alpha\n',
        1
    )

# 2) Добавить флаг canEditColorSku после articleBaseClean
if 'val canEditColorSku = articleBaseClean.isNotBlank()' not in s:
    s = s.replace(
        '    val articleBaseClean = articleBase.trim()\n',
        '    val articleBaseClean = articleBase.trim()\n    val canEditColorSku = articleBaseClean.isNotBlank()\n',
        1
    )

# 3) Отключить все OutlinedTextField/TextField, которые редактируют skuText
def patch_textfields(src: str) -> str:
    patterns = [
        r'OutlinedTextField\(\s*\n(?P<indent>\s*)(?P<first>value\s*=\s*[^\n]*skuText[^\n]*\n)',
        r'TextField\(\s*\n(?P<indent>\s*)(?P<first>value\s*=\s*[^\n]*skuText[^\n]*\n)',
    ]

    for pat in patterns:
        def repl(m):
            indent = m.group('indent')
            first = m.group('first')
            block = m.group(0)
            if 'enabled = canEditColorSku' in block:
                return block
            return block.replace(first, f'enabled = canEditColorSku,\n{indent}{first}', 1)
        src = re.sub(pat, repl, src, flags=re.DOTALL)
    return src

s = patch_textfields(s)

# 4) Если есть Row/Column для цветовых sku, приглушаем его
# Патчим самые вероятные контейнеры рядом с skuText
s = re.sub(
    r'(Row\(\s*modifier\s*=\s*Modifier(?:\.[^\n)]*)?\s*\)\s*\{\s*\n(?:.*\n){0,20}?.*skuText.*\n)',
    lambda m: m.group(1).replace('Modifier', 'Modifier.alpha(if (canEditColorSku) 1f else 0.5f)', 1)
    if 'alpha(if (canEditColorSku) 1f else 0.5f)' not in m.group(1) else m.group(1),
    s,
    flags=re.DOTALL
)

# 5) Добавить предупреждение один раз рядом с блоком debug/save, если найдём articleBaseClean usage
warn = '''
        if (!canEditColorSku) {
            Text(
                text = "Сначала заполни базовый артикул, потом можно выбирать номера цветов.",
                color = Color.Gray
            )
        }
'''
if warn.strip() not in s:
    # вставляем перед первым использованием colorDrafts в UI
    m = re.search(r'\n(\s*)colorDrafts\.forEach', s)
    if m:
        indent = m.group(1)
        s = s[:m.start()] + '\n' + '\n'.join(indent + line if line else '' for line in warn.strip('\n').split('\n')) + s[m.start():]

# 6) Если есть кнопки выбора/сброса рядом с skuText, пытаемся отключить их
# Патчим IconButton/Button рядом с skuText по enabled=
def patch_buttons(src: str) -> str:
    pats = [
        r'IconButton\(\s*\n(?P<indent>\s*)(?!enabled\s*=)(?P<body>(?:.*\n){0,8}?.*skuText.*(?:.*\n){0,12}?)\)',
        r'Button\(\s*\n(?P<indent>\s*)(?!enabled\s*=)(?P<body>(?:.*\n){0,8}?.*skuText.*(?:.*\n){0,12}?)\)',
        r'TextButton\(\s*\n(?P<indent>\s*)(?!enabled\s*=)(?P<body>(?:.*\n){0,8}?.*skuText.*(?:.*\n){0,12}?)\)',
    ]
    for pat in pats:
        def repl(m):
            indent = m.group('indent')
            full = m.group(0)
            if 'enabled = canEditColorSku' in full:
                return full
            return full.replace(f'\n{indent}', f'\nenabled = canEditColorSku,\n{indent}', 1)
        src = re.sub(pat, repl, src, flags=re.DOTALL)
    return src

s = patch_buttons(s)

p.write_text(s, encoding="utf-8")
print("PATCH_OK")
PY

echo '=== VERIFY ==='
grep -n 'canEditColorSku\|alpha(if (canEditColorSku)\|Сначала заполни базовый артикул\|enabled = canEditColorSku' "$FILE" || true
