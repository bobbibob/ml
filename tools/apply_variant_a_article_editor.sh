#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE" || { echo "ERROR: $FILE not found"; exit 1; }

python3 - <<'PY'
from __future__ import annotations
from pathlib import Path
import re

path = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
txt = path.read_text(encoding="utf-8")

# ---------- helpers ----------
def find_outlined_textfield_block_by_label(source: str, label_text: str) -> tuple[int,int]:
    """
    Finds the OutlinedTextField(...) block that contains label Text("<label_text>").
    Returns (start_index, end_index) in source.
    """
    # find label occurrence
    m = re.search(r'Text\(\s*"(?:' + re.escape(label_text) + r')"\s*\)', source)
    if not m:
        raise RuntimeError(f'Cannot find label Text("{label_text}")')

    # search backward for "OutlinedTextField("
    start = source.rfind("OutlinedTextField(", 0, m.start())
    if start < 0:
        raise RuntimeError(f'Cannot find OutlinedTextField( for label "{label_text}"')

    # now parse parentheses from start to find matching close
    i = start
    depth = 0
    in_str = False
    esc = False
    while i < len(source):
        ch = source[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    # include trailing possible .fillMaxWidth() etc? usually ends at ')'
                    end = i + 1
                    # include trailing whitespace/newline
                    while end < len(source) and source[end] in " \t\r":
                        end += 1
                    if end < len(source) and source[end] == "\n":
                        end += 1
                    return (start, end)
        i += 1
    raise RuntimeError(f'Unbalanced parentheses while parsing OutlinedTextField for "{label_text}"')

def find_colors_section(source: str) -> tuple[int,int]:
    """
    Find a section starting at a line that contains Text("Цвета") and ends
    right before the next top-level OutlinedTextField(label=...) that is 'Цена продажи'
    OR before the final Save button area if present.
    We'll keep it conservative: capture from Text("Цвета") line until just before the Price OutlinedTextField block.
    """
    m = re.search(r'Text\(\s*"(?:Цвета)"\s*\)', source)
    if not m:
        # sometimes it's Text(text="Цвета")
        m = re.search(r'Text\(\s*text\s*=\s*"(?:Цвета)"\s*\)', source)
    if not m:
        raise RuntimeError('Cannot find Colors section header Text("Цвета")')

    # start at the beginning of that line
    start = source.rfind("\n", 0, m.start()) + 1

    # end: just before the "Цена продажи" OutlinedTextField block
    p_start, _ = find_outlined_textfield_block_by_label(source, "Цена продажи")
    end = p_start
    return (start, end)

# ---------- locate blocks ----------
price_start, price_end = find_outlined_textfield_block_by_label(txt, "Цена продажи")
price_block = txt[price_start:price_end]

colors_start, colors_end = find_colors_section(txt)
colors_block_old = txt[colors_start:colors_end]

# ---------- build new colors block (Variant A) ----------
# We will REPLACE the old colors block with a new one.
# It will rely only on local state (mutableStateListOf for colors, mutableStateMapOf for prices).
# If your file already has colors list state, it won't break — we use a unique name to avoid collisions.

new_colors_block = r'''
      // --- Цвета (Variant A: цены по цветам) ---
      val __colors = remember { mutableStateListOf<String>() }
      val __colorPrices = remember { mutableStateMapOf<String, String>() }

      // true = одна цена для всех цветов
      var __priceForAll by remember { mutableStateOf(true) }

      // используем существующую "Цена продажи" как global price (если в файле она называется иначе — не важно,
      // это поле ниже мы просто переносим; тут добавим отдельный текст для отображения глобальной цены рядом с цветами)
      // Поэтому здесь просто держим буфер на случай, если ниже переменная называется иначе.
      var __globalPriceShadow by remember { mutableStateOf("") }

      Text("Цвета", style = MaterialTheme.typography.titleMedium, color = TextBlack)
      Spacer(Modifier.height(8.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = __priceForAll,
          onCheckedChange = { checked ->
            // если снимаем галку — раскидаем global price по цветам (если у цвета цена пустая)
            if (__priceForAll && !checked) {
              val gp = __globalPriceShadow.trim()
              if (gp.isNotEmpty()) {
                for (c in __colors) {
                  val cur = (__colorPrices[c] ?: "").trim()
                  if (cur.isEmpty()) __colorPrices[c] = gp
                }
              }
            }
            __priceForAll = checked
          }
        )
        Column {
          Text("Цена для всех цветов", color = TextBlack)
          Text("если выключить — цена по каждому цвету", style = MaterialTheme.typography.bodySmall, color = TextGray)
        }
      }

      Spacer(Modifier.height(8.dp))

      if (__priceForAll) {
        // поле "Цена для всех" (shadow), чтобы при снятии галки можно было раскидать по цветам
        OutlinedTextField(
          value = __globalPriceShadow,
          onValueChange = { __globalPriceShadow = it },
          label = { Text("Цена для всех") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
      }

      // UI: список цветов + цена справа
      __colors.forEach { c ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = c,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = TextBlack
          )
          Spacer(Modifier.width(8.dp))
          OutlinedTextField(
            value = (__colorPrices[c] ?: ""),
            onValueChange = { v -> __colorPrices[c] = v },
            label = { Text("Цена") },
            singleLine = true,
            enabled = !__priceForAll,
            modifier = Modifier.width(140.dp)
          )
        }
        Spacer(Modifier.height(8.dp))
      }

      // добавить цвет
      var __newColor by remember { mutableStateOf("") }
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = __newColor,
          onValueChange = { __newColor = it },
          label = { Text("Новый цвет") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = {
            val v = __newColor.trim()
            if (v.isNotEmpty() && !__colors.contains(v)) {
              __colors.add(v)
              if (__priceForAll) {
                val gp = __globalPriceShadow.trim()
                if (gp.isNotEmpty()) __colorPrices[v] = gp
              }
            }
            __newColor = ""
          }
        ) { Text("Добавить") }
      }

      Spacer(Modifier.height(16.dp))
      // --- /Цвета ---
'''

# ---------- move colors above price ----------
# Remove old colors section
txt2 = txt[:colors_start] + txt[colors_end:]

# After removal, the price indices may shift; find price block again in txt2
p2_start, p2_end = find_outlined_textfield_block_by_label(txt2, "Цена продажи")
price_block2 = txt2[p2_start:p2_end]

# Remove price block from its current place
txt3 = txt2[:p2_start] + txt2[p2_end:]

# Insert new colors block where the price used to be OR better: insert right BEFORE the price position.
# We'll insert colors block at the original price position in txt3 (same p2_start).
insert_at = p2_start

# Place: colors block -> price block
txt4 = txt3[:insert_at] + new_colors_block + price_block2 + txt3[insert_at:]

# ---------- ensure imports (best-effort) ----------
def ensure_import(source: str, imp: str) -> str:
    if re.search(r'^\s*import\s+' + re.escape(imp) + r'\s*$', source, flags=re.M):
        return source
    # insert after package + existing imports block
    m = re.search(r'^(package[^\n]*\n)(\s*import[^\n]*\n)+', source, flags=re.M)
    if m:
        end = m.end()
        return source[:end] + f"import {imp}\n" + source[end:]
    # fallback: after package line
    m2 = re.search(r'^(package[^\n]*\n)', source, flags=re.M)
    if m2:
        end = m2.end()
        return source[:end] + f"\nimport {imp}\n" + source[end:]
    return source

needed_imports = [
    "androidx.compose.runtime.mutableStateMapOf",
    "androidx.compose.runtime.mutableStateListOf",
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.setValue",
    "androidx.compose.foundation.layout.width",
]
for imp in needed_imports:
    txt4 = ensure_import(txt4, imp)

path.write_text(txt4, encoding="utf-8")
print("OK: patched AddEditArticleScreen.kt (Variant A: colors above price + priceForAll toggle)")
PY

echo "DONE. Now run:"
echo "  git add $FILE"
echo "  git commit -m \"UI: Variant A colors above price + price per color\""
echo "  git push"
