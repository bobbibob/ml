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
      Spacer(Modifier.height(10.dp))
        value = hypothesis,
        onValueChange = { hypothesis = it },
        label = { Text("Гипотеза") },
        value = priceText,
        onValueChange = { priceText = it },
        label = { Text("Цена продажи") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      Text("Тип карточки")
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(
          selected = cardType == CardType.CLASSIC,
          onClick = { cardType = CardType.CLASSIC },
          label = { Text("Классика") }
        )
          selected = cardType == CardType.PREMIUM,
          onClick = { cardType = CardType.PREMIUM },
          label = { Text("Премиум") }
        value = cogsText,
        onValueChange = { cogsText = it },
        label = { Text("Себестоимость") },
      Spacer(Modifier.height(14.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { pickPhoto.launch("image/*") }) { Text("Загрузить фото") }
        if (photoPath != null) {
          Text("OK", modifier = Modifier.padding(top = 10.dp))
        }
      Text("Цвета")
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = newColor,
          onValueChange = { newColor = it },
          label = { Text("Новый цвет") },
          modifier = Modifier.weight(1f)
        Button(
          onClick = {
            val v = newColor.trim()
            if (v.isNotBlank() && !colors.contains(v)) colors.add(v)
            newColor = ""
          }
        ) { Text("Добавить") }
      if (colors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          colors.forEach { c ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(c)
              TextButton(onClick = { colors.remove(c) }) { Text("Удалить") }
            }
      Spacer(Modifier.height(16.dp))
          enabled = !saving,
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
        ) { Text(if (saving) "Сохраняю…" else "Сохранить") }
        OutlinedButton(onClick = onDone) { Text("Отмена") }
      if (status.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(status)
}
private fun saveImageToPrivate(ctx: Context, bagId: String, uri: Uri): File {
  val dir = File(ctx.filesDir, "user_images")
  if (!dir.exists()) dir.mkdirs()
  val dst = File(dir, "${bagId}.jpg")
  ctx.contentResolver.openInputStream(uri).use { input ->
    requireNotNull(input) { "Cannot open input stream" }
    dst.outputStream().use { out -> input.copyTo(out) }
  return dst
