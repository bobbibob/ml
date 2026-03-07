#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
sync = Path("app/src/main/java/com/ml/app/data/PackDbSync.kt")

s = vm.read_text()
s = s.replace("      if (has) PackDbSync.refreshMergedDb(ctx)\n", "")
s = s.replace("          PackDbSync.refreshMergedDb(ctx)\n", "")
vm.write_text(s)

p = sync.read_text()
old = """  fun dbFileToUse(ctx: Context): File {
    val merged = mergedDbFile(ctx)
    return if (merged.exists() && merged.length() > 0) merged else PackPaths.dbFile(ctx)
  }"""
new = """  fun dbFileToUse(ctx: Context): File {
    return PackPaths.dbFile(ctx)
  }"""
p = p.replace(old, new, 1)
sync.write_text(p)

print("patched")
PY

git add app/src/main/java/com/ml/app/ui/SummaryViewModel.kt app/src/main/java/com/ml/app/data/PackDbSync.kt
git commit -m "disable merged db for crash isolation"
git push
