#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE"

python3 - <<'PY'
import re
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8")

# ---------- 0) normalize line endings ----------
s = s.replace("\r\n", "\n")

# ---------- 1) hard-fix package line (it must be single) ----------
lines = s.split("\n")
# find first package occurrence anywhere
pkg_line_idx = None
pkg_name = None
for i, ln in enumerate(lines):
    m = re.search(r'^\s*package\s+([A-Za-z0-9_.]+)', ln)
    if m:
        pkg_line_idx = i
        pkg_name = m.group(1)
        break
if not pkg_name:
    raise SystemExit("ERROR: cannot find package line in file")

# remove any 'import ...' that got appended to package line
fixed_pkg = f"package {pkg_name}"

# ---------- 2) collect all valid imports anywhere ----------
imports = []
for m in re.finditer(r'^\s*import\s+[A-Za-z0-9_.]+(\s+as\s+[A-Za-z0-9_]+)?\s*$', s, flags=re.M):
    imp = "import " + m.group(0).strip().split("import",1)[1].strip()
    if imp not in imports:
        imports.append(imp)

# required imports for this screen
required = [
    "import androidx.compose.ui.Alignment",
    "import androidx.compose.ui.text.style.TextOverflow",
    "import androidx.activity.compose.rememberLauncherForActivityResult",
    "import androidx.activity.result.contract.ActivityResultContracts",
    "import androidx.compose.runtime.mutableStateMapOf",
]
for imp in required:
    if imp not in imports:
        imports.append(imp)

# ---------- 3) strip ALL import lines and any duplicate/garbage before package ----------
# Remove every line that starts with import
body_lines = []
for ln in lines:
    if re.match(r'^\s*import\s+', ln):
        continue
    body_lines.append(ln)
body = "\n".join(body_lines)

# Remove everything BEFORE first 'package ...' and replace with fixed package
# Find first occurrence of 'package ...' in body and cut from there
m = re.search(r'^\s*package\s+[A-Za-z0-9_.]+.*$', body, flags=re.M)
if not m:
    raise SystemExit("ERROR: package line disappeared after stripping imports")
body_from_pkg = body[m.start():]

# Replace the first package line with fixed_pkg (single line)
body_from_pkg = re.sub(r'^\s*package\s+[A-Za-z0-9_.]+.*$', fixed_pkg, body_from_pkg, count=1, flags=re.M)

# ---------- 4) rebuild file with clean header ----------
imports_sorted = sorted(imports)
header = fixed_pkg + "\n\n" + "\n".join(imports_sorted) + "\n\n"

# remove any extra package duplicates later in file (keep only first)
rest = body_from_pkg.split("\n", 1)[1] if "\n" in body_from_pkg else ""
rest = re.sub(r'^\s*package\s+[A-Za-z0-9_.]+.*$\n?', "", rest, flags=re.M)

s = header + rest

# ---------- helpers: detect real variable names ----------
def find_checked_var_near(label_text: str):
    idx = s.find(label_text)
    if idx == -1:
        return None
    chunk = s[max(0, idx-500): idx+800]
    # prefer Checkbox(checked = xxx
    m = re.search(r'checked\s*=\s*([A-Za-z_]\w*)', chunk)
    return m.group(1) if m else None

def find_value_var_for_label(label_text: str):
    # find OutlinedTextField label "Цена для всех" and read value = <var>
    pattern = r'label\s*=\s*\{\s*Text\("' + re.escape(label_text) + r'"\)\s*\}'
    m = re.search(pattern, s)
    if not m:
        return None
    # back a bit from label start to find nearest 'value = ...'
    start = max(0, m.start()-350)
    chunk = s[start:m.start()]
    mm = None
    for cand in re.finditer(r'value\s*=\s*([A-Za-z_]\w*)', chunk):
        mm = cand
    return mm.group(1) if mm else None

def find_colors_list_var():
    idx = s.find('Text("Новый цвет")')
    if idx == -1:
        return None
    chunk = s[idx: idx+2500]
    m = re.search(r'(\b[A-Za-z_]\w*)\s*\.\s*add\s*\(', chunk)
    return m.group(1) if m else None

flag_var = find_checked_var_near("Цена для всех цветов") or find_checked_var_near("Цена для всех") or "priceForAllEnabled"
common_price_var = find_value_var_for_label("Цена для всех") or "priceAll"
colors_var = find_colors_list_var() or "colors"

# ---------- 5) ensure per-color map exists ----------
if re.search(r'\bcolorPrices\b', s) is None:
    # insert inside the main composable right after its opening '{'
    m = re.search(r'@Composable\s+fun\s+[A-Za-z_]\w*\s*\([^)]*\)\s*\{', s)
    if m:
        ins = m.end()
        s = s[:ins] + "\n  // цены по цветам (variant A)\n  val colorPrices = remember { mutableStateMapOf<String, String>() }\n" + s[ins:]
    else:
        # if we can't find function header, don't insert (but usually it exists)
        pass

# ---------- 6) replace wrong placeholder variable names if they exist ----------
# (your log shows priceForAllEnabled / priceAll unresolved)
s = re.sub(r'\bpriceForAllEnabled\b', flag_var, s)
s = re.sub(r'\bpriceAll\b', common_price_var, s)

# ---------- 7) ensure Variant A colors list block exists (or fix existing) ----------
marker = "/* ML_VARIANT_A_COLORS_V2 */"
end_marker = "/* ML_VARIANT_A_COLORS_V2 END */"
block = f'''
{marker}
Spacer(Modifier.height(12.dp))

// список цветов + цены
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
{end_marker}
'''

if marker in s and end_marker in s:
    # replace whole existing block with corrected one
    s = re.sub(re.escape(marker) + r'.*?' + re.escape(end_marker), block.strip("\n"), s, flags=re.S)
else:
    # insert after the Row that contains "Новый цвет"
    idx = s.find('Text("Новый цвет")')
    if idx != -1:
        after_nl = s.find("\n", idx)
        rest = s[after_nl:]
        rest_lines = rest.splitlines(True)
        insert_at = None
        for k, ln in enumerate(rest_lines):
            if ln.strip() == "}":
                insert_at = after_nl + sum(len(x) for x in rest_lines[:k+1])
                break
        if insert_at:
            s = s[:insert_at] + "\n" + block + "\n" + s[insert_at:]

# ---------- 8) Add missing import for remember (in case it was lost) ----------
# (Usually already exists, but safe)
if "import androidx.compose.runtime.remember" not in s and re.search(r'\bremember\s*\{', s):
    # add it to imports section
    # find end of import block (first blank line after imports)
    m = re.search(r'^(package[^\n]*\n\n)(.*?)(\n\n)', s, flags=re.S)
    if m:
        pkg = m.group(1)
        imp_block = m.group(2).splitlines()
        if "import androidx.compose.runtime.remember" not in imp_block:
            imp_block.append("import androidx.compose.runtime.remember")
        imp_block = sorted(set(imp_block))
        s = pkg + "\n".join(imp_block) + m.group(3) + s[m.end():]

p.write_text(s, encoding="utf-8")
print("OK: hard-fixed header/imports + repaired Variant A names")
print(f"Detected vars: flag={flag_var}, commonPrice={common_price_var}, colorsList={colors_var}")
PY

echo "DONE. Commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"Fix AddEditArticleScreen header/imports + repair Variant A vars\""
echo "  git push"
