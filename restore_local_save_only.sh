#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text()

old = '''                Button(
                    onClick = {
                        scope.launch {
                            val id = selectedBagId ?: name.trim().ifBlank { return@launch }

                            kotlin.runCatching {
                                repo.upsertBagUser(
                                    bagId = id,
                                    name = name.ifBlank { null },
                                    hypothesis = hypothesis.ifBlank { null },
                                    price = priceAll.replace(",", ".").toDoubleOrNull(),
                                    cogs = cost.replace(",", ".").toDoubleOrNull(),
                                    cardType = cardType,
                                    photoPath = photoPath
                                )

                                repo.replaceBagUserColors(
                                    id,
                                    colorDrafts.map { it.color }
                                )

                                repo.replaceBagColorPrices(
                                    id,
                                    colorDrafts.map {
                                        BagColorPriceRow(
                                            color = it.color,
                                            price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                        )
                                    }
                                )

                                PackUploadManager.saveUserChangesAndUpload(ctx)
                            }

                            onDone?.invoke()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {'''

new = '''                Button(
                    onClick = {
                        scope.launch {
                            val id = selectedBagId ?: name.trim().ifBlank { return@launch }

                            repo.upsertBagUser(
                                bagId = id,
                                name = name.ifBlank { null },
                                hypothesis = hypothesis.ifBlank { null },
                                price = priceAll.replace(",", ".").toDoubleOrNull(),
                                cogs = cost.replace(",", ".").toDoubleOrNull(),
                                cardType = cardType,
                                photoPath = photoPath
                            )

                            repo.replaceBagUserColors(
                                id,
                                colorDrafts.map { it.color }
                            )

                            repo.replaceBagColorPrices(
                                id,
                                colorDrafts.map {
                                    BagColorPriceRow(
                                        color = it.color,
                                        price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                    )
                                }
                            )

                            onDone?.invoke()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {'''

if old not in s:
    raise SystemExit("save block not found")

s = s.replace(old, new, 1)
s = s.replace('import com.ml.app.data.PackUploadManager\n', '', 1)

p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "restore local save only"
git push
