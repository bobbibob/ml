#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")
screen = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")

# ---------- SummaryViewModel.kt ----------
s = vm.read_text()

if "import com.ml.app.data.PackUploadManager" not in s:
    s = s.replace(
        "import com.ml.app.data.PackDbSync\n",
        "import com.ml.app.data.PackDbSync\nimport com.ml.app.data.PackUploadManager\n",
        1
    )

if "fun syncPackNow()" not in s:
    insert_after = '''  fun backFromArticleEditor() {
    _state.value = _state.value.copy(mode = ScreenMode.Timeline)
  }
'''
    new_block = insert_after + '''
  fun syncPackNow() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Синхронизация pack…")
        PackUploadManager.saveUserChangesAndUpload(ctx)
        _state.value = _state.value.copy(loading = false, status = "Pack синхронизирован")
        refreshAfterSync()
      } catch (t: Throwable) {
        _state.value = _state.value.copy(
          loading = false,
          status = when (t.message?.trim()) {
            "Сначала обнови пакет" -> "Сначала обнови пакет"
            else -> "Ошибка sync pack: ${t.message}"
          }
        )
      }
    }
  }

'''
    if insert_after not in s:
        raise SystemExit("cannot find insertion point in SummaryViewModel.kt")
    s = s.replace(insert_after, new_block, 1)

vm.write_text(s)

# ---------- SummaryScreen.kt ----------
u = screen.read_text()

# add button above status text
old = '''        if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
'''
new = '''        if (state.mode !is ScreenMode.ArticleEditor) {
          Button(
            onClick = { vm.syncPackNow() },
            enabled = !state.loading,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
          ) {
            Text("Синхронизировать pack")
          }
        }

        if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
'''
if old not in u:
    raise SystemExit("status block not found in SummaryScreen.kt")
u = u.replace(old, new, 1)

screen.write_text(u)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/SummaryViewModel.kt app/src/main/java/com/ml/app/ui/SummaryScreen.kt
git commit -m "add pack sync button"
git push
