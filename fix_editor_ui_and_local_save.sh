#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
summary = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")

s = ui.read_text()

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

old_block = """            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,"""

new_block = """            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!photoPath.isNullOrBlank()) {
                    AsyncImage(
                        model = photoPath,
                        contentDescription = name,
                        modifier = Modifier
                            .width(96.dp)
                            .height(96.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Обновить фото")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,"""

s = s.replace(old_block, new_block, 1)

old_button = """            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (photoPath.isNullOrBlank()) "Загрузить фото" else "Сменить фото")
            }"""

s = s.replace(old_button, "", 1)

old_save = """            Button(
                onClick = { onDone?.invoke() },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
            }"""

new_save = """            Button(
                onClick = {
                    scope.launch {
                        val id = selectedBagId ?: name.trim().ifBlank { return@launch }
                        repo.upsertBagUser(
                            bagId = id,
                            name = name.ifBlank { null },
                            hypothesis = hypothesis.ifBlank { null },
                            price = priceAll.toDoubleOrNull(),
                            cogs = cost.toDoubleOrNull(),
                            cardType = cardType,
                            photoPath = photoPath
                        )
                        repo.replaceBagUserColors(id, colors.toList())
                        onDone?.invoke()
                    }
                },
                enabled = hasChanges,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
            }"""

s = s.replace(old_save, new_save, 1)

ui.write_text(s)

ss = summary.read_text()
old_bar = """    ArticleBottomBar(
      onArticleClick = { vm.openArticleEditor() },
      modifier = Modifier.align(Alignment.BottomCenter)
    )"""

new_bar = """    if (state.mode !is ScreenMode.ArticleEditor) {
      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )
    }"""

ss = ss.replace(old_bar, new_bar, 1)
summary.write_text(ss)

print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt app/src/main/java/com/ml/app/ui/SummaryScreen.kt
git commit -m "fix editor photo ui local save and hide bottom bar" || true
git push
