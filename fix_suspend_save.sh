#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text()

if "import androidx.compose.runtime.rememberCoroutineScope" not in s:
    s = s.replace(
        "import androidx.compose.runtime.remember\n",
        "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n",
        1
    )

if "import kotlinx.coroutines.launch" not in s:
    s = s.replace(
        "import java.util.UUID\n",
        "import java.util.UUID\nimport kotlinx.coroutines.launch\n",
        1
    )

if "val scope = rememberCoroutineScope()" not in s:
    s = s.replace(
        "    val repo = remember { SQLiteRepo(ctx) }\n",
        "    val repo = remember { SQLiteRepo(ctx) }\n    val scope = rememberCoroutineScope()\n",
        1
    )

old = '''                Button(
                    onClick = {
                        val id = selectedBagId
                        if (!id.isNullOrBlank()) {
                            kotlin.runCatching {
                                repo.replaceBagColorPrices(
                                    id,
                                    colorDrafts.map {
                                        BagColorPriceRow(
                                            color = it.color,
                                            price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                        )
                                    }
                                )
                            }
                        }
                        showExitDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {'''

new = '''                Button(
                    onClick = {
                        scope.launch {
                            val id = selectedBagId
                            if (!id.isNullOrBlank()) {
                                kotlin.runCatching {
                                    repo.replaceBagColorPrices(
                                        id,
                                        colorDrafts.map {
                                            BagColorPriceRow(
                                                color = it.color,
                                                price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                            )
                                        }
                                    )
                                }
                            }
                            showExitDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {'''

if old not in s:
    raise SystemExit("save button block not found")

s = s.replace(old, new, 1)
p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "wrap color price save in coroutine" || true
git push
