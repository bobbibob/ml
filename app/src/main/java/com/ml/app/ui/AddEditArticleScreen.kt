package com.ml.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ml.app.data.CardTypeStore
import com.ml.app.data.SQLiteRepo
import com.ml.app.domain.CardType
import kotlinx.coroutines.launch

@Composable
fun AddEditArticleScreen(
  bagId: String?,              // null => create new
  onDone: () -> Unit
) {
  val repo = remember { SQLiteRepo(LocalAppContext.current) }
  val typeStore = remember { CardTypeStore(LocalAppContext.current) } // пишет в bag_card_type
  val scope = rememberCoroutineScope()

  var realBagId by remember { mutableStateOf(bagId ?: ("user_" + System.currentTimeMillis())) }

  var name by remember { mutableStateOf("") }
  var hypothesis by remember { mutableStateOf("") }
  var priceText by remember { mutableStateOf("") }
  var cogsText by remember { mutableStateOf("") }
  var cardType by remember { mutableStateOf(CardType.CLASSIC) }
  var photoUri by remember { mutableStateOf<String?>(null) }
  var colors by remember { mutableStateOf(listOf<String>()) }
  var newColor by remember { mutableStateOf("") }

  var status by remember { mutableStateOf("") }
  var loading by remember { mutableStateOf(false) }

  val pickImage = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) photoUri = uri.toString()
  }

  // load existing
  LaunchedEffect(realBagId) {
    loading = true
    status = "Загружаю…"
    try {
      val u = repo.getBagUser(realBagId)
      if (u != null) {
        name = u.name ?: ""
        hypothesis = u.hypothesis ?: ""
        priceText = u.price?.toString() ?: ""
        cogsText = u.cogs?.toString() ?: ""
        photoUri = u.photoPath
        val t = u.cardType ?: typeStore.getType(realBagId)?.name
        cardType = if (t == "PREMIUM") CardType.PREMIUM else CardType.CLASSIC
      } else {
        // defaults for new
        val t = typeStore.getType(realBagId) ?: CardType.CLASSIC
        cardType = t
      }
      colors = repo.getBagUserColors(realBagId)
      status = ""
    } catch (t: Throwable) {
      status = "Ошибка: ${t.message}"
    } finally {
      loading = false
    }
  }

  fun parseDoubleOrNull(s: String): Double? {
    val x = s.trim().replace(",", ".")
    if (x.isBlank()) return null
    return x.toDoubleOrNull()
  }

  suspend fun save() {
    loading = true
    status = "Сохраняю…"
    try {
      val price = parseDoubleOrNull(priceText)
      val cogs = parseDoubleOrNull(cogsText)

      repo.upsertBagUser(
        bagId = realBagId,
        name = name.trim().ifBlank { null },
        hypothesis = hypothesis.trim().ifBlank { null },
        price = price,
        cogs = cogs,
        cardType = cardType.name,
        photoPath = photoUri
      )
      repo.replaceBagUserColors(realBagId, colors)

      // отдельно обновим bag_card_type (чтобы формула прибыли работала сразу)
      typeStore.setType(realBagId, cardType)

      status = "Сохранено"
      onDone()
    } catch (t: Throwable) {
      status = "Ошибка сохранения: ${t.message}"
    } finally {
      loading = false
    }
  }

  Column(Modifier.fillMaxSize().padding(12.dp)) {
    Text(
      text = if (bagId == null) "Добавить артикул" else "Редактировать артикул",
      style = MaterialTheme.typography.titleLarge
    )

    Spacer(Modifier.height(10.dp))

    if (status.isNotBlank()) {
      Text(status)
      Spacer(Modifier.height(8.dp))
    }

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Название") },
          modifier = Modifier.fillMaxWidth()
        )
      }

      item {
        OutlinedTextField(
          value = hypothesis,
          onValueChange = { hypothesis = it },
          label = { Text("Гипотеза") },
          modifier = Modifier.fillMaxWidth()
        )
      }

      item {
        OutlinedTextField(
          value = priceText,
          onValueChange = { priceText = it },
          label = { Text("Цена продажи") },
          modifier = Modifier.fillMaxWidth()
        )
      }

      item {
        OutlinedTextField(
          value = cogsText,
          onValueChange = { cogsText = it },
          label = { Text("Себестоимость") },
          modifier = Modifier.fillMaxWidth()
        )
      }

      item {
        Text("Тип карточки")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
      }

      item {
        Text("Фото")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(onClick = { pickImage.launch("image/*") }) { Text("Выбрать фото") }
          if (!photoUri.isNullOrBlank()) {
            Text("Выбрано", modifier = Modifier.padding(top = 10.dp))
          }
        }
      }

      item {
        Text("Цвета")
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedTextField(
            value = newColor,
            onValueChange = { newColor = it },
            label = { Text("Добавить цвет") },
            modifier = Modifier.weight(1f)
          )
          Button(
            onClick = {
              val c = newColor.trim()
              if (c.isNotBlank() && !colors.contains(c)) {
                colors = (colors + c).sorted()
              }
              newColor = ""
            }
          ) { Text("Добавить") }
        }

        Spacer(Modifier.height(10.dp))

        if (colors.isEmpty()) {
          Text("Пока нет цветов")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { c ->
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(c, modifier = Modifier.weight(1f))
                TextButton(onClick = { colors = colors.filterNot { it == c } }) { Text("Удалить") }
              }
            }
          }
        }
      }
    }

    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = onDone,
        modifier = Modifier.weight(1f),
        enabled = !loading
      ) { Text("Закрыть") }

      Button(
        onClick = { scope.launch { save() } },
        modifier = Modifier.weight(1f),
        enabled = !loading
      ) { Text("Сохранить") }
    }
  }
}

/**
 * Чтобы не тащить LocalContext во все места.
 */
private object LocalAppContext {
  val current: android.content.Context
    @Composable get() = androidx.compose.ui.platform.LocalContext.current.applicationContext
}
