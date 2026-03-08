#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

ui = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
vm = Path("app/src/main/java/com/ml/app/ui/SummaryViewModel.kt")

# ---------- AddEditArticleScreen.kt ----------
u = ui.read_text()

u = re.sub(
    r'''LaunchedEffect\(selectedBagId\)\s*\{.*?^\s*\}''',
    '''LaunchedEffect(selectedBagId) {
        val id = selectedBagId ?: return@LaunchedEffect
        loadBagFromPicker(id)

        val row = kotlin.runCatching { repo.getBagUser(id) }.getOrNull()
        if (row != null) {
            if (!row.name.isNullOrBlank()) name = row.name
            if (!row.hypothesis.isNullOrBlank()) hypothesis = row.hypothesis
            if (row.price != null) priceAll = row.price.toString()
            if (row.cogs != null) cost = row.cogs.toString()
            if (!row.cardType.isNullOrBlank()) cardType = row.cardType
            if (!row.photoPath.isNullOrBlank()) photoPath = row.photoPath
        }

        val seed = kotlin.runCatching { repo.getBagEditorSeed(id) }.getOrNull()
        if (seed != null) {
            if (name.isBlank()) name = seed.bagName
            if (hypothesis.isBlank()) hypothesis = seed.hypothesis.orEmpty()
            if (priceAll.isBlank()) priceAll = seed.price?.toString().orEmpty()
            if (cost.isBlank()) cost = seed.cogs?.toString().orEmpty()

            colorDrafts.clear()
            colorDrafts.addAll(
                seed.colors.distinct().map { color ->
                    ColorDraft(color = color, priceText = "")
                }
            )
        }

        val savedPrices = kotlin.runCatching { repo.getBagColorPrices(id) }.getOrDefault(emptyList())
        priceForAllEnabled = savedPrices.none { it.price != null }

        if (savedPrices.isNotEmpty()) {
            for (i in colorDrafts.indices) {
                val item = colorDrafts[i]
                val saved = savedPrices.firstOrNull { it.color == item.color }?.price
                if (saved != null) {
                    colorDrafts[i] = item.copy(priceText = saved.toString())
                }
            }
        }
    }''',
    u,
    count=1,
    flags=re.DOTALL | re.MULTILINE
)
ui.write_text(u)

# ---------- SummaryViewModel.kt ----------
s = vm.read_text()

if "import org.json.JSONObject" not in s:
    s = s.replace(
        "import kotlinx.coroutines.launch\n",
        "import kotlinx.coroutines.launch\nimport org.json.JSONObject\n",
        1
    )

if "import java.io.File" not in s:
    s = s.replace(
        "import java.time.LocalDate\n",
        "import java.time.LocalDate\nimport java.io.File\n",
        1
    )

s = re.sub(
    r'''fun syncIfChanged\(\)\s*\{.*?^\s*\}''',
    '''fun syncIfChanged() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _state.value = _state.value.copy(loading = true, status = "Checking updates…")

        val hasLocal = PackPaths.dbFile(ctx).exists() && PackPaths.imagesDir(ctx).exists()
        val localManifestFile = PackPaths.manifestFile(ctx)
        val localVersion = if (localManifestFile.exists()) {
          kotlin.runCatching { JSONObject(localManifestFile.readText()).optInt("version", 0) }.getOrDefault(0)
        } else {
          0
        }

        val tmpDir = File(ctx.cacheDir, "pack_refresh_check")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val zip = r2.downloadPackZip()
        ZipUtil.unzipToDir(zip, tmpDir)

        val remoteManifestFile = File(tmpDir, "manifest.json")
        val remoteVersion = if (remoteManifestFile.exists()) {
          kotlin.runCatching { JSONObject(remoteManifestFile.readText()).optInt("version", 0) }.getOrDefault(0)
        } else {
          0
        }

        if (!hasLocal) {
          _state.value = _state.value.copy(status = "Downloading…")
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.mergedDbFile(ctx).delete()
          PackDbSync.refreshMergedDb(ctx)

          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Downloaded")
          refreshAfterSync()
          return@launch
        }

        if (remoteVersion > localVersion) {
          _state.value = _state.value.copy(status = "Updating…")
          ZipUtil.unzipToDir(zip, PackPaths.packDir(ctx))
          PackDbSync.mergedDbFile(ctx).delete()
          PackDbSync.refreshMergedDb(ctx)

          _state.value = _state.value.copy(hasPack = true, loading = false, status = "Updated")
          refreshAfterSync()
        } else {
          _state.value = _state.value.copy(loading = false, status = "No changes")
          refreshAfterSync()
        }
      } catch (t: Throwable) {
        _state.value = _state.value.copy(loading = false, status = "Sync error: ${t.message}")
      }
    }
  }''',
    s,
    count=1,
    flags=re.DOTALL | re.MULTILINE
)

vm.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt app/src/main/java/com/ml/app/ui/SummaryViewModel.kt
git commit -m "fix common price load and manifest refresh"
git push
