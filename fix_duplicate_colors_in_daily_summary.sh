#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/ml/app/data/SQLiteRepo.kt")
s = p.read_text()

old = '''  suspend fun listSummaryBagColorMeta(): List<SummaryBagColorMeta> = withContext(Dispatchers.IO) {
    val today = LocalDate.now().toString()

    val currentInStock = getResolvedStocksForDate(today)
        .filter { it.stock > 0.0 }
        .groupBy { it.bagId }

    val bagMeta = listStockBagMeta()

    bagMeta.mapNotNull { bag ->
        val colors = currentInStock[bag.bagId]
            ?.map { it.color }
            ?.distinct()
            ?.sortedBy { it.lowercase() }
            .orEmpty()

        if (colors.isEmpty()) null
        else SummaryBagColorMeta(
            bagId = bag.bagId,
            bagName = bag.bagName,
            photoPath = bag.photoPath,
            colors = colors
        )
    }
  }'''

new = '''  suspend fun listSummaryBagColorMeta(): List<SummaryBagColorMeta> = withContext(Dispatchers.IO) {
    fun normalizedColorKey(value: String): String {
      return value
        .trim()
        .replace(Regex("\\\\s+"), " ")
        .lowercase()
        .trimEnd('.')
    }

    val today = LocalDate.now().toString()

    val currentInStock = getResolvedStocksForDate(today)
        .filter { it.stock > 0.0 }
        .groupBy { it.bagId }

    val bagMeta = listStockBagMeta()

    bagMeta.mapNotNull { bag ->
        val rawColors = currentInStock[bag.bagId]
            ?.map { it.color.trim().replace(Regex("\\\\s+"), " ") }
            .orEmpty()

        val deduped = linkedMapOf<String, String>()
        for (color in rawColors) {
          val key = normalizedColorKey(color)
          if (key.isNotBlank() && key !in deduped) {
            deduped[key] = color
          }
        }

        val colors = deduped.values
            .sortedBy { it.lowercase() }

        if (colors.isEmpty()) null
        else SummaryBagColorMeta(
            bagId = bag.bagId,
            bagName = bag.bagName,
            photoPath = bag.photoPath,
            colors = colors
        )
    }
  }'''

if old not in s:
    raise SystemExit("listSummaryBagColorMeta block not found")

s = s.replace(old, new, 1)
p.write_text(s)
print("patched")
PY

git add app/src/main/java/com/ml/app/data/SQLiteRepo.kt
git commit -m "dedupe daily summary colors by normalized key" || true
git push
