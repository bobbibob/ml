#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
s = p.read_text()

old = "val rows = repo.loadForDate(_state.value.selectedDate.toString())"

new = '''
        val date = _state.value.selectedDate.toString()
        val rows = repo.loadForDate(date)

        val resolved = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }

        val rowsWithResolvedStock = rows.map { row ->
            val stocks = resolved[row.bagId]
                ?.map { com.ml.app.domain.ColorValue(it.color, it.stock) }
                ?: row.stockByColors

            row.copy(
                stockByColors = stocks
            )
        }
'''

if old not in s:
    raise SystemExit("rows load block not found")

s = s.replace(old, new, 1)

# заменить использование rows -> rowsWithResolvedStock
s = s.replace(
    "val ids = rows.map { it.bagId }.distinct()",
    "val ids = rowsWithResolvedStock.map { it.bagId }.distinct()",
    1
)

s = s.replace(
    "rows = rows,",
    "rows = rowsWithResolvedStock,",
    1
)

p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/SummaryViewModel.kt
git commit -m "apply stock overrides to details screen"
git push
