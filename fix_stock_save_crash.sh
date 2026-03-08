#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")
s = p.read_text()

old = '''                                            repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                            PackUploadManager.saveUserChangesAndUpload(ctx)
                                            editingBagId = null
                                            drafts.clear()
                                            reload()
'''

new = '''                                            repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                            PackUploadManager.saveUserChangesAndUpload(ctx)

                                            items = items.map {
                                                if (it.bagId == bag.bagId) {
                                                    it.copy(colors = rows)
                                                } else {
                                                    it
                                                }
                                            }

                                            editingBagId = null
                                            drafts.clear()
'''

if old not in s:
    raise SystemExit("save block not found")

s = s.replace(old, new, 1)
p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "fix stock save crash by removing immediate reload" || true
git push
