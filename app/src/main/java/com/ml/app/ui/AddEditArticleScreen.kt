package com.ml.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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

@Composable
fun AddEditArticleScreen(
  bagId: String?,
  onDone: () -> Unit
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember { SQLiteRepo(ctx) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  val typeStore = remember { CardTypeStore(ctx) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }

  var id by remember { mutableStateOf(bagId) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }

  var name by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var hypothesis by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var priceText by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var cogsText by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var cardType by remember { mutableStateOf(CardType.CLASSIC) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var photoPath by remember { mutableStateOf<String?>(null) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }

  val colors = remember { mutableStateListOf<String>() }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var newColor by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }

  var status by remember { mutableStateOf("") }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }
  var saving by remember { mutableStateOf(false) }
    var editingArticleId by remember { mutableStateOf<Long?>(null) }

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

      OutlinedTextField(
        value = priceText,
        onValueChange = { priceText = it },
        label = { Text("Цена продажи") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(10.dp))

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

fun loadArticleForEdit(
    article: Article,
    onLoad: (String, String, String) -> Unit
) {
    onLoad(article.name, article.description, article.photoUri ?: "")
}

fun deleteArticleById(
    id: Long,
    repository: ArticleRepository
) {
    repository.deleteById(id)
}
