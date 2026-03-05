#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE"

python3 - <<'PY'
import re
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text(encoding="utf-8").replace("\r\n", "\n")

# 1) package name (try to salvage even if broken line)
m = re.search(r'package\s+([A-Za-z0-9_.]+)', s)
if not m:
    raise SystemExit("ERROR: cannot find 'package ...' in file at all")
pkg = m.group(1)

# 2) cut body from first @Composable (or first fun that looks like screen)
m2 = re.search(r'^\s*@Composable\b', s, flags=re.M)
if not m2:
    # fallback: first function named AddEditArticleScreen
    m2 = re.search(r'^\s*fun\s+AddEditArticleScreen\b', s, flags=re.M)
if not m2:
    raise SystemExit("ERROR: cannot find '@Composable' (or fun AddEditArticleScreen) to cut body")

body = s[m2.start():].lstrip("\n")

# 3) clean header + wide import set (safe)
imports = [
"import android.net.Uri",
"import androidx.activity.compose.rememberLauncherForActivityResult",
"import androidx.activity.result.contract.ActivityResultContracts",
"import androidx.compose.foundation.Image",
"import androidx.compose.foundation.background",
"import androidx.compose.foundation.clickable",
"import androidx.compose.foundation.layout.*",
"import androidx.compose.foundation.lazy.LazyColumn",
"import androidx.compose.foundation.lazy.items",
"import androidx.compose.foundation.shape.RoundedCornerShape",
"import androidx.compose.foundation.text.KeyboardOptions",
"import androidx.compose.material3.*",
"import androidx.compose.runtime.*",
"import androidx.compose.runtime.mutableStateMapOf",
"import androidx.compose.runtime.mutableStateListOf",
"import androidx.compose.ui.Alignment",
"import androidx.compose.ui.Modifier",
"import androidx.compose.ui.draw.clip",
"import androidx.compose.ui.graphics.Color",
"import androidx.compose.ui.platform.LocalContext",
"import androidx.compose.ui.text.font.FontWeight",
"import androidx.compose.ui.text.input.KeyboardType",
"import androidx.compose.ui.text.style.TextOverflow",
"import androidx.compose.ui.unit.dp",
"import androidx.navigation.NavController",
"import kotlinx.coroutines.launch",
]

header = "package " + pkg + "\n\n" + "\n".join(imports) + "\n\n"

# 4) ensure placeholders exist if referenced
needs_flag = "priceForAllEnabled" in body and not re.search(r'\bpriceForAllEnabled\b\s*by\s*remember', body)
needs_all  = "priceAll" in body and not re.search(r'\bpriceAll\b\s*by\s*remember', body)

if needs_flag or needs_all:
    # inject right after opening brace of the FIRST composable function
    mfun = re.search(r'@Composable\s+fun\s+[A-Za-z_]\w*\s*\([^)]*\)\s*\{', body)
    if mfun:
        inject = "\n  // --- injected state (repair) ---\n"
        if needs_flag:
            inject += "  var priceForAllEnabled by remember { mutableStateOf(true) }\n"
        if needs_all:
            inject += "  var priceAll by remember { mutableStateOf(\"\") }\n"
        inject += "  // --- end injected state ---\n"
        pos = mfun.end()
        body = body[:pos] + inject + body[pos:]

out = header + body
p.write_text(out, encoding="utf-8")
print("OK: repaired header/imports; injected states:",
      f"priceForAllEnabled={needs_flag}, priceAll={needs_all}")
PY

echo "Now commit & push:"
echo "  git add $FILE"
echo "  git commit -m \"Repair AddEditArticleScreen header/imports\""
echo "  git push"
