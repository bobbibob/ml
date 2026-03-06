#!/usr/bin/env bash
set -euo pipefail

SUMMARY="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"
EDITOR="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

if [ ! -f "$SUMMARY" ]; then
  echo "Не найден $SUMMARY"
  exit 1
fi

mkdir -p "$(dirname "$EDITOR")"

cp "$SUMMARY" "$SUMMARY.bak"
[ -f "$EDITOR" ] && cp "$EDITOR" "$EDITOR.bak" || true

echo "== 1. Возвращаем дату в SummaryScreen, если её нет =="
if ! grep -q 'Дата:' "$SUMMARY"; then
  awk '
  BEGIN { inserted=0 }
  {
    print
    if (!inserted && $0 ~ /selectedDate/ && $0 ~ /=/) {
      print "        Text(\"Дата: ${state.selectedDate}\", maxLines = 1, overflow = TextOverflow.Ellipsis)"
      inserted=1
    }
  }' "$SUMMARY" > "$SUMMARY.tmp"
  mv "$SUMMARY.tmp" "$SUMMARY"
fi

echo "== 2. Ставим нормальный каркас AddEditArticleScreen =="
cat > "$EDITOR" <<'EOF'
package com.ml.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddEditArticleScreen(
    bagId: String? = null,
    onDone: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }

    var priceForAllEnabled by remember { mutableStateOf(true) }
    var priceAll by remember { mutableStateOf("") }

    var cardType by remember { mutableStateOf("classic") }
    var newColor by remember { mutableStateOf("") }

    val colors = remember { mutableStateListOf<String>() }
    val colorPrices = remember { mutableStateMapOf<String, String>() }

    val hasChanges =
        name.isNotBlank() ||
        hypothesis.isNotBlank() ||
        cost.isNotBlank() ||
        priceAll.isNotBlank() ||
        colors.isNotEmpty()

    fun addColor() {
        val value = newColor.trim()
        if (value.isBlank()) return
        if (!colors.contains(value)) {
            colors.add(value)
            if (!priceForAllEnabled) {
                colorPrices[value] = priceAll
            }
        }
        newColor = ""
    }

    fun removeColor(color: String) {
        colors.remove(color)
        colorPrices.remove(color)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = if (bagId.isNullOrBlank()) "Добавить артикул" else "Редактировать артикул",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = hypothesis,
            onValueChange = { hypothesis = it },
            label = { Text("Гипотеза") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Цвета",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = priceForAllEnabled,
                onCheckedChange = { checked ->
                    priceForAllEnabled = checked
                    if (!checked) {
                        for (c in colors) {
                            if ((colorPrices[c] ?: "").isBlank()) {
                                colorPrices[c] = priceAll
                            }
                        }
                    }
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Цена для всех цветов")
                Text(
                    text = "если выключить — цена по каждому цвету",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = priceAll,
            onValueChange = {
                priceAll = it
                if (!priceForAllEnabled) {
                    for (c in colors) {
                        if ((colorPrices[c] ?: "").isBlank()) {
                            colorPrices[c] = it
                        }
                    }
                }
            },
            enabled = priceForAllEnabled,
            label = { Text("Цена для всех") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newColor,
                onValueChange = { newColor = it },
                label = { Text("Новый цвет") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Button(onClick = { addColor() }) {
                Text("Добавить")
            }
        }

        if (colors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
        }

        for (color in colors) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = color,
                    modifier = Modifier.weight(1f)
                )

                if (!priceForAllEnabled) {
                    OutlinedTextField(
                        value = colorPrices[color] ?: "",
                        onValueChange = { colorPrices[color] = it },
                        label = { Text("Цена") },
                        modifier = Modifier.width(140.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                OutlinedButton(
                    onClick = { removeColor(color) }
                ) {
                    Text("Удалить")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Тип карточки",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = cardType == "classic",
                onClick = { cardType = "classic" },
                label = { Text("Классика") }
            )
            FilterChip(
                selected = cardType == "premium",
                onClick = { cardType = "premium" },
                label = { Text("Премиум") }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = cost,
            onValueChange = { cost = it },
            label = { Text("Себестоимость") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Загрузить фото")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onDone?.invoke() },
            enabled = hasChanges,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (bagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
        }
    }
}
EOF

echo "== 3. Коммит и пуш =="
git add "$SUMMARY" "$EDITOR"
git commit -m "UI: restore date and replace stub with real article editor scaffold" || echo "Нет изменений для коммита"
git push
