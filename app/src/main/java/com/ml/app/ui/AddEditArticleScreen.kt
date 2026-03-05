package com.ml.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.
// per-color prices
val colorPrices = remember { mutableStateMapOf<String, String>() }
rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ml.app.data.CardTypeStore
import com.ml.app.data.SQLiteRepo
import com.ml.app.domain.CardType
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun AddEditArticleScreen(
  bagId: String?,
  onDone: () -> Unit
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { SQLiteRepo(ctx) }
  val typeStore = remember { CardTypeStore(ctx) }

  var id by remember { mutableStateOf(bagId) }

  var name by remember { mutableStateOf("") }
  var hypothesis by remember { mutableStateOf("") }
  var priceText by remember { mutableStateOf("") }
  var cogsText by remember { mutableStateOf("") }
  var cardType by remember { mutableStateOf(CardType.CLASSIC) }
  var photoPath by remember { mutableStateOf<String?>(null) }

  val colors = remember { mutableStateListOf<String>() }
  var newColor by remember { mutableStateOf("") }

  var status by remember { mutableStateOf("") }
  var saving by remember { mutableStateOf(false) }

  fun parseDoubleOrNull(s: String): Double? =
    s.trim().replace(",", ".").toDoubleOrNull()

  // load existing if editing
  LaunchedEffect(id) {
    val curId = id ?: return@LaunchedEffect
    val row = repo.getBagUser(curId)
    if (row != null) {
      name = row.name.orEmpty()
      hypothesis = row.hypothesis.orEmpty()
      priceText = row.price?.toString().orEmpty()
      cogsText = row.cogs?.toString().orEmpty()
      photoPath = row.photoPath
      cardType = when ((row.cardType ?: "CLASSIC").uppercase()) {
        "PREMIUM" -> CardType.PREMIUM
        else -> CardType.CLASSIC
      }
    } else {
      // если нет bag_user записи — тип берём из bag_card_type
      val types = typeStore.getTypes(listOf(curId))
      cardType = types[curId] ?: CardType.CLASSIC
    }
    colors.clear()
    colors.addAll(repo.getBagUserColors(curId))
  }

  // photo picker
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      try {
        val curId = id ?: ("u_" + System.currentTimeMillis().toString()).also { id = it }
        val dst = saveImageToPrivate(ctx, curId, uri)
        photoPath = dst.absolutePath
        status = "Фото выбрано"
      } catch (t: Throwable) {
        status = "Ошибка фото: ${t.message}"
      }
    }
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(12.dp)
  ) {
    Column(
      modifier = Modifier
        .padding(14.dp)
        .verticalScroll(rememberScrollState())
    ) {
      Text(
        text = if (id == null) "Добавить артикул" else "Редактировать артикул",
        style = MaterialTheme.typography.titleLarge
      )
      Spacer(Modifier.height(12.dp))

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Название") },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = hypothesis,
        onValueChange = { hypothesis = it },
        label = { Text("Гипотеза") },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(10.dp))

      
      // --- Цвета (Variant A: цены по цветам) ---
      val __colors = remember { mutableStateListOf<String>() }
      val __colorPrices = remember { mutableStateMapOf<String, String>() }

      // true = одна цена для всех цветов
      var __priceForAll by remember { mutableStateOf(true) }

      // используем существующую "Цена продажи" как global price (если в файле она называется иначе — не важно,
      // это поле ниже мы просто переносим; тут добавим отдельный текст для отображения глобальной цены рядом с цветами)
      // Поэтому здесь просто держим буфер на случай, если ниже переменная называется иначе.
      var __globalPriceShadow by remember { mutableStateOf("") }

      Text("Цвета", style = MaterialTheme.typography.titleMedium, color = Color.Black)
      Spacer(Modifier.height(8.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = __priceForAll,
          onCheckedChange = { checked ->
            // если снимаем галку — раскидаем global price по цветам (если у цвета цена пустая)
            if (__priceForAll && !checked) {
              val gp = __globalPriceShadow.trim()
              if (gp.isNotEmpty()) {
                for (c in __colors) {
                  val cur = (__colorPrices[c] ?: "").trim()
                  if (cur.isEmpty()) __colorPrices[c] = gp
                }
              }
            }
            __priceForAll = checked
          }
        )
        Column {
          Text("Цена для всех цветов", color = Color.Black)
          Text("если выключить — цена по каждому цвету", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
      }

      Spacer(Modifier.height(8.dp))

      if (__priceForAll) {
        // поле "Цена для всех" (shadow), чтобы при снятии галки можно было раскидать по цветам
        OutlinedTextField(
          value = __globalPriceShadow,
          onValueChange = { __globalPriceShadow = it },
          label = { Text("Цена для всех") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
      }

      // UI: список цветов + цена справа
      __colors.forEach { c ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = c,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.Black
          )
          Spacer(Modifier.width(8.dp))
          OutlinedTextField(
            value = (__colorPrices[c] ?: ""),
            onValueChange = { v -> __colorPrices[c] = v },
            label = { Text("Цена") },
            singleLine = true,
            enabled = !__priceForAll,
            modifier = Modifier.width(140.dp)
          )
        }
        Spacer(Modifier.height(8.dp))
      }

      // добавить цвет
      var __newColor by remember { mutableStateOf("") }
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = __newColor,
          onValueChange = { __newColor = it },
          label = { Text("Новый цвет") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = {
            val v = __newColor.trim()
            if (v.isNotEmpty() && !__colors.contains(v)) {
              __colors.add(v)
              if (__priceForAll) {
                val gp = __globalPriceShadow.trim()
                if (gp.isNotEmpty()) __colorPrices[v] = gp
              }

/* ML_COLOR_LIST_V1 */
Spacer(Modifier.height(12.dp))

// Список цветов (с ценой рядом, если отключили "цена для всех")
colors.forEach { c ->
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
  ) {
    Text(
      text = c,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )

    if (!priceForAllEnabled) {
      OutlinedTextField(
        value = (colorPrices[c] ?: priceAll),
        onValueChange = { v -> colorPrices[c] = v },
        singleLine = true,
        modifier = Modifier.width(120.dp),
        placeholder = { Text("Цена") }
      )
      Spacer(Modifier.width(8.dp))
    } else {
      // когда цена общая — показываем её как подсказку (не редактируется)
      Text(
        text = if (priceAll.isBlank()) "—" else priceAll,
        modifier = Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
      )
    }

    TextButton(
      onClick = {
        colors.remove(c)
        colorPrices.remove(c)
      }
    ) { Text("Удалить") }
  }

  Spacer(Modifier.height(8.dp))
}
/* ML_COLOR_LIST_V1 END */
            }
            __newColor = ""
          }
        ) { Text("Добавить") }
      }

      Spacer(Modifier.height(16.dp))
      // --- /Цвета ---

      Text("Тип карточки")
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(
          selected = cardType == CardType.CLASSIC,
          onClick = { cardType = CardType.CLASSIC },
          label = { Text("Классика") }
        )
        FilterChip(
          selected = cardType == CardType.PREMIUM,
          onClick = { cardType = CardType.PREMIUM },
          label = { Text("Премиум") }
        )
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = cogsText,
        onValueChange = { cogsText = it },
        label = { Text("Себестоимость") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(Modifier.height(14.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { pickPhoto.launch("image/*") }) { Text("Загрузить фото") }
        if (photoPath != null) {
          Text("OK", modifier = Modifier.padding(top = 10.dp))
        }
      }

      Spacer(Modifier.height(14.dp))

      Text("Цвета")
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = newColor,
          onValueChange = { newColor = it },
          label = { Text("Новый цвет") },
          modifier = Modifier.weight(1f)
        )
        Button(
          onClick = {
            val v = newColor.trim()
            if (v.isNotBlank() && !colors.contains(v)) colors.add(v)
            newColor = ""
          }
        ) { Text("Добавить") }
      }

      if (colors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          colors.forEach { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(c)
              TextButton(onClick = { colors.remove(c) }) { Text("Удалить") }
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          enabled = !saving,
          onClick = {
            scope.launch {
              saving = true
              status = ""
              try {
                val curId = id ?: ("u_" + System.currentTimeMillis().toString()).also { id = it }

                val price = parseDoubleOrNull(priceText)
                val cogs = parseDoubleOrNull(cogsText)

                // 1) bag_user + colors
                repo.upsertBagUser(
                  bagId = curId,
                  name = name.ifBlank { null },
                  hypothesis = hypothesis.ifBlank { null },
                  price = price,
                  cogs = cogs,
                  cardType = cardType.name,
                  photoPath = photoPath
                )
                repo.replaceBagUserColors(curId, colors.toList())

                // 2) bag_card_type (чтобы прибыль считалась сразу)
                typeStore.setType(curId, cardType)

                status = "Сохранено"
                onDone()
              } catch (t: Throwable) {
                status = "Ошибка: ${t.message}"
              } finally {
                saving = false
              }
            }
          }
        ) { Text(if (saving) "Сохраняю…" else "Сохранить") }

        OutlinedButton(onClick = onDone) { Text("Отмена") }
      }

      if (status.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(status)
      }
    }
  }
}

private fun saveImageToPrivate(ctx: Context, bagId: String, uri: Uri): File {
  val dir = File(ctx.filesDir, "user_images")
  if (!dir.exists()) dir.mkdirs()
  val dst = File(dir, "${bagId}.jpg")
  ctx.contentResolver.openInputStream(uri).use { input ->
    requireNotNull(input) { "Cannot open input stream" }
    dst.outputStream().use { out -> input.copyTo(out) }
  }
  return dst
}
