#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/ui/StockScreen.kt")
s = p.read_text()

# 1) целые числа в отображении
s = s.replace(
    '                                            Text(stock.toString())',
    '                                            Text(stock.toInt().toString())'
)

s = s.replace(
    '                                                value = drafts[key] ?: stock.toString(),',
    '                                                value = drafts[key] ?: stock.toInt().toString(),'
)

s = s.replace(
    '                                        drafts["${bag.bagId}::$color"] = stock.toString()',
    '                                        drafts["${bag.bagId}::$color"] = stock.toInt().toString()'
)

# 2) сохранение только целых
old_save = '''                                        val rows = bag.colors.map { (color, stock) ->
                                            val key = "${bag.bagId}::$color"
                                            color to ((drafts[key] ?: stock.toString()).replace(",", ".").toDoubleOrNull() ?: stock)
                                        }'''
new_save = '''                                        val rows = bag.colors.map { (color, stock) ->
                                            val key = "${bag.bagId}::$color"
                                            val value = (drafts[key] ?: stock.toInt().toString()).trim()
                                            color to (value.toIntOrNull()?.toDouble() ?: stock.toInt().toDouble())
                                        }'''
if old_save in s:
    s = s.replace(old_save, new_save, 1)

# 3) BackHandler: если редактируем блок, назад = отмена редактирования
old_back = '    BackHandler { onBack() }\n'
new_back = '''    BackHandler {
        if (editingBagId != null) {
            editingBagId = null
            drafts.clear()
        } else {
            onBack()
        }
    }
'''
if old_back in s:
    s = s.replace(old_back, new_back, 1)

# 4) добавить кнопку "Отменить" рядом с "Сохранить остатки"
old_buttons = '''                        if (editingBagId == bag.bagId) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val rows = bag.colors.map { (color, stock) ->
                                            val key = "${bag.bagId}::$color"
                                            val value = (drafts[key] ?: stock.toInt().toString()).trim()
                                            color to (value.toIntOrNull()?.toDouble() ?: stock.toInt().toDouble())
                                        }
                                        repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                        PackUploadManager.saveUserChangesAndUpload(ctx)
                                        editingBagId = null
                                        drafts.clear()
                                        reload()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Сохранить остатки")
                            }
                        } else {
                            Button(
                                onClick = {
                                    editingBagId = bag.bagId
                                    drafts.clear()
                                    for ((color, stock) in bag.colors) {
                                        drafts["${bag.bagId}::$color"] = stock.toInt().toString()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Редактировать")
                            }
                        }'''
new_buttons = '''                        if (editingBagId == bag.bagId) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        editingBagId = null
                                        drafts.clear()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Отменить")
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val rows = bag.colors.map { (color, stock) ->
                                                val key = "${bag.bagId}::$color"
                                                val value = (drafts[key] ?: stock.toInt().toString()).trim()
                                                color to (value.toIntOrNull()?.toDouble() ?: stock.toInt().toDouble())
                                            }
                                            repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                            PackUploadManager.saveUserChangesAndUpload(ctx)
                                            editingBagId = null
                                            drafts.clear()
                                            reload()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Сохранить")
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    editingBagId = bag.bagId
                                    drafts.clear()
                                    for ((color, stock) in bag.colors) {
                                        drafts["${bag.bagId}::$color"] = stock.toInt().toString()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Редактировать")
                            }
                        }'''
if old_buttons in s:
    s = s.replace(old_buttons, new_buttons, 1)
else:
    raise SystemExit("edit/save button block not found")

p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/StockScreen.kt
git commit -m "make stock editor integer-only and add cancel behavior"
git push
