package com.ml.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditArticleScreen(
  // делаем опциональным для совместимости со старым вызовом без navController
  navController: NavController? = null,
  bagId: String? = null,
  // добавляем обратно, чтобы старый SummaryScreen компилился
  onDone: (() -> Unit)? = null
) {
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()

  var name by remember { mutableStateOf("") }
  var sku by remember { mutableStateOf(bagId ?: "") }

  // Photo
  var photoUri by remember { mutableStateOf<Uri?>(null) }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    photoUri = uri
  }

  // Variant A: Colors + prices
  val colors = remember { mutableStateListOf<String>() }
  var newColor by remember { mutableStateOf("") }

  var priceForAllEnabled by remember { mutableStateOf(true) }
  var priceAll by remember { mutableStateOf("") }
  val colorPrices = remember { mutableStateMapOf<String, String>() }

  var dirty by remember { mutableStateOf(false) }

  fun ensureColorPrice(color: String) {
    if (!colorPrices.containsKey(color)) {
      colorPrices[color] = priceAll
    }
  }

  fun addColor(c: String) {
    val cc = c.trim()
    if (cc.isBlank()) return
    if (colors.contains(cc)) return
    colors.add(cc)
    ensureColorPrice(cc)
    dirty = true
  }

  fun removeColor(c: String) {
    colors.remove(c)
    colorPrices.remove(c)
    dirty = true
  }

  fun setPriceForAll(enabled: Boolean) {
    if (priceForAllEnabled == enabled) return
    priceForAllEnabled = enabled
    if (!enabled) {
      for (c in colors) colorPrices[c] = priceAll
    }
    dirty = true
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            if (bagId == null) "Добавить артикул" else "Редактировать артикул",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        },
        navigationIcon = {
          TextButton(
            onClick = {
              // если есть navController — назад, иначе зовём onDone (совместимость)
              if (navController != null) navController.popBackStack() else onDone?.invoke()
            }
          ) { Text("Назад") }
        }
      )
    },
    bottomBar = {
      Surface(tonalElevation = 3.dp) {
        Row(
          Modifier
            .fillMaxWidth()
            .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Spacer(Modifier.weight(1f))
          Button(
            enabled = dirty,
            onClick = {
              // пока заглушка: только снять dirty, чтобы UI был рабочим
              scope.launch {
                dirty = false
                onDone?.invoke()
              }
            }
          ) { Text("Сохранить") }
        }
      }
    }
  ) { pad ->
    Column(
      Modifier
        .padding(pad)
        .padding(12.dp)
        .verticalScroll(scroll)
    ) {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it; dirty = true },
        label = { Text("Название") },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = sku,
        onValueChange = { sku = it; dirty = true },
        label = { Text("Артикул / SKU") },
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(Modifier.height(14.dp))

      // Photo (single button)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { pickPhoto.launch("image/*") }) { Text("Загрузить фото") }
        Spacer(Modifier.width(10.dp))
        Text(
          text = photoUri?.toString() ?: "Фото не выбрано",
          color = Color.Gray,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
      }

      Spacer(Modifier.height(18.dp))

      // Colors first (Variant A)
      Text("Цвета", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(Modifier.height(8.dp))

      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = newColor,
          onValueChange = { newColor = it },
          label = { Text("Добавить цвет") },
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = { addColor(newColor); newColor = "" }) { Text("Добавить") }
      }

      Spacer(Modifier.height(10.dp))

      if (colors.isEmpty()) {
        Text("Нет цветов", color = Color.Gray)
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          colors.forEach { c ->
            Row(
              Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(c, modifier = Modifier.weight(1f), color = Color.Black)

              OutlinedTextField(
                value = colorPrices[c] ?: "",
                onValueChange = { colorPrices[c] = it; dirty = true },
                label = { Text("Цена") },
                enabled = !priceForAllEnabled,
                singleLine = true,
                // пишем полным путём, чтобы не зависеть от импорта KeyboardOptions
                keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
                  keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                modifier = Modifier.width(140.dp)
              )

              Spacer(Modifier.width(8.dp))
              TextButton(onClick = { removeColor(c) }) { Text("Удалить") }
            }
          }
        }
      }

      Spacer(Modifier.height(18.dp))

      Text("Цена", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(Modifier.height(8.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = priceForAllEnabled, onCheckedChange = { setPriceForAll(it) })
        Column {
          Text("Цена для всех цветов", color = Color.Black)
          Text(
            "Если выключить — цена задаётся отдельно для каждого цвета",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      OutlinedTextField(
        value = priceAll,
        onValueChange = { priceAll = it; dirty = true },
        label = { Text("Цена (общая)") },
        enabled = priceForAllEnabled,
        singleLine = true,
        keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
          keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        ),
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(Modifier.height(80.dp))
    }
  }
}
