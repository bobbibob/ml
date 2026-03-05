#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE"

python3 - <<'PY'
import re
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8")

# ---------- 1) FIX IMPORTS (robust) ----------
lines = s.splitlines(True)

# split any line that accidentally contains multiple imports in one line
fixed_lines = []
for ln in lines:
    if "import " in ln and not ln.lstrip().startswith("import "):
        # Sometimes broken like: "package ... import x"
        # split by 'import ' occurrences keeping them
        parts = re.split(r'(?=\bimport\s+)', ln)
        for part in parts:
            if part.strip():
                fixed_lines.append(part if part.endswith("\n") else part + "\n")
    else:
        fixed_lines.append(ln)

lines = fixed_lines

pkg_i = None
for i, ln in enumerate(lines):
    if ln.lstrip().startswith("package "):
        pkg_i = i
        break
if pkg_i is None:
    raise SystemExit("No package line found")

# collect all imports anywhere
imports = []
body = []
for ln in lines:
    if ln.lstrip().startswith("import "):
        imp = ln.strip()
        if imp not in imports:
            imports.append(imp)
    else:
        body.append(ln)

# remove any stray "imports are only allowed..." damage: ensure no 'import' remains in body
body2 = []
for ln in body:
    if ln.lstrip().startswith("import "):
        continue
    body2.append(ln)
body = body2

# ensure required imports
need = [
  "import androidx.compose.runtime.mutableStateMapOf",
  "import androidx.compose.ui.Alignment",
  "import androidx.compose.ui.text.style.TextOverflow",
]
for imp in need:
    if imp not in imports:
        imports.append(imp)

# rebuild with imports right after package
# keep everything except old imports already stripped
# find package line in body (it exists)
rebuilt = []
found_pkg = False
for ln in body:
    rebuilt.append(ln)
    if not found_pkg and ln.lstrip().startswith("package "):
        found_pkg = True
        rebuilt.append("\n")
        for imp in sorted(imports):
            rebuilt.append(imp + "\n")
        rebuilt.append("\n")

s = "".join(rebuilt)

# ---------- helpers to auto-detect variable names ----------
def find_var_name_near(text, pattern=r'\b(var|val)\s+([A-Za-z_]\w*)\s+by\s+remember'):
    idx = s.find(text)
    if idx == -1:
        return None
    start = max(0, idx - 2000)
    chunk = s[start:idx]
    m = None
    for mm in re.finditer(pattern, chunk):
        m = mm
    return m.group(2) if m else None

def find_list_var_used_with_add_near(text):
    idx = s.find(text)
    if idx == -1:
        return None
    chunk = s[idx: idx + 2500]
    # look for something.add(...)
    m = re.search(r'(\b[A-Za-z_]\w*)\s*\.\s*add\s*\(', chunk)
    return m.group(1) if m else None

# These texts already exist in твоем UI:
flag_var = find_var_name_near("Цена для всех цветов")  # boolean
common_price_var = find_var_name_near("Цена для всех")  # String
colors_var = find_list_var_used_with_add_near("Новый цвет")  # mutable list

# fallback names if not found (won't compile if wrong, but usually finds correctly)
flag_var = flag_var or "priceForAll"
common_price_var = common_price_var or "priceAll"
colors_var = colors_var or "colors"

# ---------- 2) Ensure colorPrices map exists ----------
if "mutableStateMapOf<String, String>()" not in s and "val colorPrices" not in s:
    # insert after first remember/rememberSaveable line
    m = re.search(r'^\s*(var|val)\s+[A-Za-z_]\w*\s+by\s+remember[^\n]*\n', s, flags=re.M)
    if m:
        insert_at = m.end()
        s = s[:insert_at] + '  // цены по цветам (variant A)\n  val colorPrices = remember { mutableStateMapOf<String, String>() }\n\n' + s[insert_at:]
    else:
        # insert near top inside composable: after first "{"
        m2 = re.search(r'@Composable\s+fun\s+[A-Za-z_]\w*\s*\([^)]*\)\s*\{', s)
        if m2:
            insert_at = m2.end()
            s = s[:insert_at] + '\n  // цены по цветам (variant A)\n  val colorPrices = remember { mutableStateMapOf<String, String>() }\n' + s[insert_at:]

# ---------- 3) Remove duplicate "Цена продажи" field at bottom if present ----------
# If there are two "Цена продажи" fields, we remove the second one (after the colors block)
occ = [m.start() for m in re.finditer(r'Text\("Цена продажи"\)', s)]
if len(occ) >= 2:
    second = occ[1]
    # remove nearest preceding OutlinedTextField(...) block
    start = s.rfind("OutlinedTextField(", 0, second)
    if start != -1:
        # find matching end by parentheses balance
        par = 0
        end = None
        for i,ch in enumerate(s[start:], start=start):
            if ch == "(":
                par += 1
            elif ch == ")":
                par -= 1
                if par == 0:
                    end = i+1
                    break
        if end:
            # also remove following Spacer if any
            tail = s[end:end+200]
            msp = re.match(r'\s*\n\s*Spacer\([^\n]*\)\s*\n', tail)
            if msp:
                end = end + msp.end()
            s = s[:start] + s[end:]

# ---------- 4) Insert/replace Variant A colors list UI ----------
# Place it after the "Новый цвет" row block (only once)
marker = "/* ML_VARIANT_A_COLORS_V2 */"
if marker not in s:
    idx = s.find('Text("Новый цвет")')
    if idx == -1:
        raise SystemExit('Cannot find "Новый цвет" to anchor insertion')

    # find end of the Row containing "Новый цвет" by searching forward for a line that is just "}" (first close)
    after_nl = s.find("\n", idx)
    rest = s[after_nl:]
    rest_lines = rest.splitlines(True)
    insert_at = None
    for k, ln in enumerate(rest_lines):
        if ln.strip() == "}":
            insert_at = after_nl + sum(len(x) for x in rest_lines[:k+1])
            break
    if insert_at is None:
        raise SystemExit("Cannot find insertion point after New color row")

    block = f'''
{marker}
Spacer(Modifier.height(12.dp))

// список цветов
{colors_var}.forEach {{ c ->
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {{
    Text(
      text = c,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    if (!{flag_var}) {{
      OutlinedTextField(
        value = (colorPrices[c] ?: {common_price_var}),
        onValueChange = {{ v -> colorPrices[c] = v }},
        singleLine = true,
        modifier = Modifier.width(120.dp),
        placeholder = {{ Text("Цена") }}
      )
      Spacer(Modifier.width(8.dp))
    }} else {{
      Text(
        text = if ({common_price_var}.isBlank()) "—" else {common_price_var},
        modifier = Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
      )
    }}

    TextButton(
      onClick = {{
        {colors_var}.remove(c)
        colorPrices.remove(c)
      }}
    ) {{ Text("Удалить") }}
  }}

  Spacer(Modifier.height(8.dp))
}}
/* ML_VARIANT_A_COLORS_V2 END */
'''
    s = s[:insert_at] + block + s[insert_at:]

p.write_text(s, encoding="utf-8")
print("OK: fixed imports + inserted Variant A v2")
PY

echo "DONE. Now commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"UI: fix AddEditArticleScreen imports + colors per-price (variant A)\""
echo "  git push"
