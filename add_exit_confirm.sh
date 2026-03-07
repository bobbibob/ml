#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

p = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
s = p.read_text()

if "import androidx.activity.compose.BackHandler" not in s:
    s = s.replace(
        "import androidx.activity.compose.rememberLauncherForActivityResult\n",
        "import androidx.activity.compose.BackHandler\nimport androidx.activity.compose.rememberLauncherForActivityResult\n",
        1
    )

if "import androidx.compose.material3.AlertDialog" not in s:
    s = s.replace(
        "import androidx.compose.material3.Button\n",
        "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\n",
        1
    )

marker = "    var selectedBagId by remember { mutableStateOf(bagId) }\n"
if "var showExitDialog by remember" not in s:
    s = s.replace(
        marker,
        '    var showExitDialog by remember { mutableStateOf(false) }\n' + marker,
        1
    )

if "BackHandler(enabled = true)" not in s:
    insert_after = "    fun seedColorPricesFromCommon() {\n        for (i in colorDrafts.indices) {\n            val item = colorDrafts[i]\n            if (item.priceText.isBlank()) {\n                colorDrafts[i] = item.copy(priceText = priceAll)\n            }\n        }\n    }\n"
    replacement = insert_after + '\n    BackHandler(enabled = true) {\n        showExitDialog = true\n    }\n'
    s = s.replace(insert_after, replacement, 1)

old_save = '''            Button(
                onClick = { onDone?.invoke() },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
            }'''
new_save = '''            Button(
                onClick = { showExitDialog = true },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
            }'''
s = s.replace(old_save, new_save, 1)

dialog = '''
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Выйти?") },
            text = { Text("Изменения могут быть потеряны") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onDone?.invoke()
                    }
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitDialog = false }
                ) {
                    Text("Нет")
                }
            }
        )
    }
'''
if "AlertDialog(" not in s:
    s = s.rstrip()
    if s.endswith("}"):
        s = s[:-1] + dialog + "\n}\n"

p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt
git commit -m "add exit confirmation dialog" || true
git push
