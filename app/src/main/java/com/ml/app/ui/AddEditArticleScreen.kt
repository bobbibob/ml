package com.ml.app.ui

import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.URL
import java.net.HttpURLConnection
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.BuildConfig
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ml.app.data.PackUploadManager
import com.ml.app.data.CardOverridesSync
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.SQLiteRepo.BagColorPriceRow
import com.ml.app.data.SQLiteRepo.BagPickerRow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

private data class ColorDraft(
    val color: String,
    val priceText: String = "",
    val skuText: String = ""
)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditArticleScreen(
    bagId: String? = null,
    onDone: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }
    val scope = rememberCoroutineScope()

    fun resolveApiBaseUrl(): String {
        val names = listOf("TASKS_API_BASE_URL", "API_BASE_URL", "BASE_URL")
        for (name in names) {
            val value = kotlin.runCatching {
                val f = BuildConfig::class.java.getField(name)
                f.get(null)?.toString().orEmpty()
            }.getOrDefault("")
            if (value.isNotBlank()) return value.trimEnd('/')
        }
        return ""
    }


    var showExitDialog by remember { mutableStateOf(false) }
    var photoPath by remember { mutableStateOf<String?>(null) }
        var saveError by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoPath = copyImageToInternalStorage(ctx, uri)
        }
    }

    var selectedBagId by remember { mutableStateOf(bagId) }
    var tab by remember { mutableStateOf(1) }
    var bagItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }

    var name by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var deliveryFee by remember { mutableStateOf("") }
    var priceForAllEnabled by remember { mutableStateOf(true) }
    var priceAll by remember { mutableStateOf("") }
    var articleBase by remember { mutableStateOf("") }
    var cardType by remember { mutableStateOf("classic") }
    var newColor by remember { mutableStateOf("") }

    val colorDrafts = remember { mutableStateListOf<ColorDraft>() }

    fun resetForm() {
        name = ""
        hypothesis = ""
        cost = ""
        photoPath = null
        priceAll = ""
        articleBase = ""
        cardType = "classic"
        newColor = ""
        priceForAllEnabled = true
        colorDrafts.clear()
    }

    fun loadBagFromPicker(id: String) {
        val bag = bagItems.firstOrNull { it.bagId == id }
        resetForm()
        name = bag?.bagName.orEmpty()
        photoPath = bag?.photoPath
    }

    fun addColor() {
        val value = newColor.trim()
        if (value.isBlank()) return
        if (colorDrafts.none { it.color == value }) {
            val nextSuffix = ((colorDrafts.mapNotNull { it.skuText.trim().toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
            colorDrafts.add(
                ColorDraft(
                    color = value,
                    priceText = if (priceForAllEnabled) "" else priceAll,
                    skuText = nextSuffix
                )
            )
        }
        newColor = ""
    }

    fun removeColor(color: String) {
        val idx = colorDrafts.indexOfFirst { it.color == color }
        if (idx >= 0) colorDrafts.removeAt(idx)
    }

    fun seedColorPricesFromCommon() {
        for (i in colorDrafts.indices) {
            val item = colorDrafts[i]
            if (item.priceText.isBlank()) {
                colorDrafts[i] = item.copy(priceText = priceAll)
            }
        }
    }

    val hasChanges =
        name.isNotBlank() ||
        hypothesis.isNotBlank() ||
        cost.isNotBlank() ||
        priceAll.isNotBlank() ||
        colorDrafts.isNotEmpty() ||
        !photoPath.isNullOrBlank()

    BackHandler {
        if (hasChanges) {
            showExitDialog = true
        } else {
onDone?.invoke()
        }
    }

    LaunchedEffect(Unit) {
        kotlin.runCatching {
            CardOverridesSync.refresh(ctx)
        }
    }

    LaunchedEffect(tab) {
        if (tab == 1) {
            bagItems = repo.loadTimeline(180)
                .flatMap { it.byBags }
                .distinctBy { it.bagId }
                .sortedBy { it.bagName.lowercase() }
                .map {
                    BagPickerRow(
                        bagId = it.bagId,
                        bagName = it.bagName,
                        photoPath = it.imagePath
                    )
                }
        }
    }

    LaunchedEffect(selectedBagId) {
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

        for (i in colorDrafts.indices) {
            val item = colorDrafts[i]
            val savedSku = kotlin.runCatching { repo.getSkuFor(id, item.color) }.getOrNull().orEmpty()
            if (savedSku.isNotBlank()) {
                val dash = savedSku.lastIndexOf("-")
                if (dash > 0 && dash < savedSku.lastIndex) {
                    val base = savedSku.substring(0, dash)
                    val suffix = savedSku.substring(dash + 1)
                    if (articleBase.isBlank()) articleBase = base
                    colorDrafts[i] = item.copy(skuText = suffix)
                } else {
                    if (articleBase.isBlank()) articleBase = savedSku
                    colorDrafts[i] = item.copy(skuText = "")
                }
            }
        }

        val serverOverride = kotlin.runCatching { repo.getServerCardOverride(id) }.getOrNull()
        if (serverOverride != null) {
            if (!serverOverride.name.isNullOrBlank()) name = serverOverride.name
            if (!serverOverride.hypothesis.isNullOrBlank()) hypothesis = serverOverride.hypothesis
            if (serverOverride.price != null) priceAll = serverOverride.price.toString()
            if (serverOverride.cogs != null) cost = serverOverride.cogs.toString()
            if (serverOverride.deliveryFee != null) deliveryFee = serverOverride.deliveryFee.toString()
            if (!serverOverride.cardType.isNullOrBlank()) cardType = serverOverride.cardType
            if (!serverOverride.photoPath.isNullOrBlank()) photoPath = serverOverride.photoPath

            if (serverOverride.colors.isNotEmpty()) {
                colorDrafts.clear()
                colorDrafts.addAll(
                    serverOverride.colors.distinct().map { color ->
                        ColorDraft(
                            color = color,
                            priceText = serverOverride.colorPrices[color]?.toString().orEmpty()
                        )
                    }
                )
            }

            priceForAllEnabled = serverOverride.colorPrices.values.none { it != null }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = tab == 0 && selectedBagId.isNullOrBlank(),
                onClick = {
                    tab = 0
                    selectedBagId = null
                    resetForm()
                },
                label = { Text("Добавить") }
            )
            FilterChip(
                selected = tab == 1,
                onClick = {
                    selectedBagId = null
                    tab = 1
                },
                label = { Text("Список") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tab == 1) {
            Text("Выберите артикул")
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(bagItems, key = { it.bagId }) { bag ->
                    Card(
                        colors = CardDefaults.cardColors(),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!bag.photoPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = bag.photoPath,
                                    contentDescription = bag.bagName,
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(72.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bag.bagName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedBagId = bag.bagId
                                    tab = 0
                                }
                            ) {
                                Text("Открыть")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (selectedBagId.isNullOrBlank()) "Добавить артикул" else "Редактировать артикул",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!photoPath.isNullOrBlank()) {
                        AsyncImage(
                            model = photoPath,
                            contentDescription = name,
                            modifier = Modifier
                                .width(96.dp)
                                .height(96.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Обновить фото")
                    }
                }

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
                            if (!checked) seedColorPricesFromCommon()
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
                            for (i in colorDrafts.indices) {
                                val item = colorDrafts[i]
                                if (item.priceText.isBlank()) {
                                    colorDrafts[i] = item.copy(priceText = it)
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

                if (colorDrafts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                for (i in colorDrafts.indices) {
                    val item = colorDrafts[i]

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.color,
                            modifier = Modifier.weight(1f)
                        )

                        if (!priceForAllEnabled) {
                            OutlinedTextField(
                                value = item.priceText,
                                onValueChange = { value ->
                                    colorDrafts[i] = item.copy(priceText = value)
                                },
                                label = { Text("Цена") },
                                modifier = Modifier.width(140.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        OutlinedButton(
                            onClick = { removeColor(item.color) }
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

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    label = { Text("Себестоимость") }
                )

                OutlinedTextField(
                    value = deliveryFee,
                    onValueChange = { deliveryFee = it },
                    label = { Text("Доставка") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Артикул карточки",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = articleBase,
                    onValueChange = { articleBase = it },
                    label = { Text("Базовый артикул") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Цифра после - по цветам",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                val suffixOptions = (1..colorDrafts.size.coerceAtLeast(1)).map { it.toString() }

                colorDrafts.forEachIndexed { index, item ->
                    var expanded by remember(item.color) { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.color,
                            modifier = Modifier.weight(1f)
                        )

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = item.skuText,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Номер") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier
                                    .width(140.dp)
                                    .menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                suffixOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            colorDrafts[index] = item.copy(skuText = option)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (!saveError.isNullOrBlank()) {
                    Text(
                        text = saveError.orEmpty(),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        scope.launch {
                            saveError = null
                            val id = selectedBagId ?: name.trim().ifBlank { return@launch }

                            repo.upsertBagUser(
                                bagId = id,
                                name = name.ifBlank { null },
                                hypothesis = hypothesis.ifBlank { null },
                                price = priceAll.replace(",", ".").toDoubleOrNull(),
                                cogs = cost.replace(",", ".").toDoubleOrNull(),
                                cardType = cardType,
                                photoPath = photoPath
                            )

                            repo.replaceBagUserColors(
                                id,
                                colorDrafts.map { it.color }
                            )

                            repo.replaceBagColorPrices(
                            

                            

                                id,
                                colorDrafts.map {
                                    BagColorPriceRow(
                                        color = it.color,
                                        price = if (priceForAllEnabled) {
                                            priceAll.replace(",", ".").toDoubleOrNull()
                                        } else {
                                            it.priceText.replace(",", ".").toDoubleOrNull()
                                        }
                                    )
                                }
                            )

                            val articleBaseClean = articleBase.trim()
                            colorDrafts.forEach {
                                val suffix = it.skuText.trim()
                                if (articleBaseClean.isNotBlank() && suffix.isNotBlank()) {
                                    repo.setSkuFor(id, it.color, articleBaseClean + "-" + suffix)
                                }
                            }

                            if (saveError.isNullOrBlank()) {
                                val apiBase = resolveApiBaseUrl()
                                if (apiBase.isBlank()) {
                                    saveError = "Не найден API base url"
                                } else {
                                    kotlin.runCatching {
                                        withContext(Dispatchers.IO) {
                                            val token = PrefsSessionStorage(ctx).getToken().orEmpty()
                                            if (token.isBlank()) error("Нет токена сессии")

                                            val colorsJson = JSONArray().apply {
                                                colorDrafts.forEach { put(it.color) }
                                            }

                                            val colorPricesJson = JSONArray().apply {
                                                colorDrafts.forEach {
                                                    put(
                                                        JSONObject().apply {
                                                            put("color", it.color)
                                                            put(
                                                                "price",
                                                                if (priceForAllEnabled) {
                                                                    priceAll.replace(",", ".").toDoubleOrNull()
                                                                } else {
                                                                    it.priceText.replace(",", ".").toDoubleOrNull()
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            }

                                            val articleBaseClean = articleBase.trim()

                                            val skuLinksJson = JSONArray().apply {
                                                colorDrafts.forEach {
                                                    val suffix = it.skuText.trim()
                                                    if (articleBaseClean.isNotBlank() && suffix.isNotBlank()) {
                                                        val sku = articleBaseClean + "-" + suffix
                                                        put(
                                                            JSONObject().apply {
                                                                put("color", it.color)
                                                                put("sku", sku)
                                                                put("article_id", repo.extractArticleId(sku))
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            val payload = JSONObject().apply {
                                                put("bag_id", id)
                                                put("name", name.ifBlank { null })
                                                put("hypothesis", hypothesis.ifBlank { null })
                                                put("price", priceAll.replace(",", ".").toDoubleOrNull())
                                                put("cogs", cost.replace(",", ".").toDoubleOrNull())
                                                put("delivery_fee", deliveryFee.replace(",", ".").toDoubleOrNull())
                                                put("card_type", cardType)
                                                put("photo_path", photoPath)
                                                put("colors", colorsJson)
                                                put("color_prices", colorPricesJson)
                                                put("sku_links", skuLinksJson)
                                            }

                                            val url = URL(apiBase + "/card_upsert")
                                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                                requestMethod = "POST"
                                                connectTimeout = 15000
                                                readTimeout = 15000
                                                doOutput = true
                                                setRequestProperty("Authorization", "Bearer " + token)
                                                setRequestProperty("Content-Type", "application/json")
                                                setRequestProperty("Accept", "application/json")
                                            }

                                            try {
                                                conn.outputStream.use { os ->
                                                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                                                }
                                                val code = conn.responseCode
                                                if (code !in 200..299) {
                                                    val err = kotlin.runCatching {
                                                        conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                                    }.getOrDefault("")
                                                    error("Server save failed: HTTP $code $err")
                                                }
                                            } finally {
                                                conn.disconnect()
                                            }
                                        }
                                    }.onFailure { t ->
                                        saveError = "Ошибка сохранения на сервер: ${t.message}"
                                    }
                                }
                            }

                            if (saveError.isNullOrBlank()) {
                                onDone?.invoke()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
                }
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Выйти?") },
            text = { Text("Изменения могут быть потеряны") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onDone?.invoke()
                    }
                ) {
                    Text("Да")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitDialog = false }
                ) {
                    Text("Нет")
                }
            }
        )
    }
}
