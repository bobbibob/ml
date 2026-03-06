#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
summary = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")

s = ui.read_text()

# imports
if "import android.content.Context" not in s:
    s = s.replace(
        "package com.ml.app.ui\n",
        "package com.ml.app.ui\n\nimport android.content.Context\nimport android.net.Uri\n",
        1,
    )

if "rememberLauncherForActivityResult" not in s:
    s = s.replace(
        "import androidx.compose.foundation.verticalScroll\n",
        "import androidx.compose.foundation.verticalScroll\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts\n",
        1,
    )

if "import java.io.File" not in s:
    s = s.replace(
        "import coil.compose.AsyncImage\n",
        "import coil.compose.AsyncImage\nimport java.io.File\nimport java.util.UUID\n",
        1,
    )

# helper
if "private fun copyImageToInternalStorage" not in s:
    helper = '''
private fun copyImageToInternalStorage(context: Context, uri: Uri): String? {
    return try {
        val ext = when (context.contentResolver.getType(uri)) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val dir = File(context.filesDir, "bag_user_photos")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "bag_${UUID.randomUUID()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (_: Throwable) {
        null
    }
}

'''
    s = s.replace("@Composable\nfun AddEditArticleScreen(", helper + "@Composable\nfun AddEditArticleScreen(", 1)

# image picker launcher
old = '    val repo = remember { SQLiteRepo(ctx) }\n'
new = '''    val repo = remember { SQLiteRepo(ctx) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoPath = copyImageToInternalStorage(ctx, uri)
        }
    }
'''
s = s.replace(old, new, 1)

# active tab logic
s = s.replace('selected = tab == 0,', 'selected = tab == 0 && selectedBagId.isNullOrBlank(),')
s = s.replace('selected = tab == 1,', 'selected = tab == 1 || selectedBagId != null,')

# current photo preview before fields
marker = '''        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
'''
insert = '''        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
'''
s = s.replace(marker, insert, 1)

text_marker = '''            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
'''
photo_block = '''            Spacer(modifier = Modifier.height(16.dp))

            if (!photoPath.isNullOrBlank()) {
                AsyncImage(
                    model = photoPath,
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedTextField(
'''
s = s.replace(text_marker, photo_block, 1)

# upload button behavior/text
s = s.replace('Button(\n                onClick = { },', 'Button(\n                onClick = { imagePicker.launch("image/*") },')
s = s.replace('Text("Загрузить фото")', 'Text(if (photoPath.isNullOrBlank()) "Загрузить фото" else "Сменить фото")')

ui.write_text(s)

ss = summary.read_text()
ss = ss.replace(
'''    ArticleBottomBar(
      onArticleClick = { vm.openArticleEditor() },
      modifier = Modifier.align(Alignment.BottomCenter)
    )
''',
'''    if (state.mode !is ScreenMode.ArticleEditor) {
      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )
    }
''',
1)
summary.write_text(ss)

print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt app/src/main/java/com/ml/app/ui/SummaryScreen.kt
git commit -m "fix editor tabs photo preview and hide bottom bar" || true
git push
