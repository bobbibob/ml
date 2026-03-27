package com.ml.app.ui

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
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.SQLiteRepo.BagColorPriceRow
import com.ml.app.data.SQLiteRepo.BagPickerRow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

private data class ColorDraft(
    val color: String,
    val priceText: String = ""
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

@Composable
fun AddEditArticleScreen(
    bagId: String? = null,
    onDone: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }
    val scope = rememberCoroutineScope()

    var showExitDialog by remember { mutableStateOf(false) }
    var photoPath by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoPath = copyImageToInternalStorage(ctx, uri)
        }
    }

    var selectedBagId by remember { mutableStateOf(bagId) }
    var isEditMode by remember { mutableStateOf(bagId.isNullOrBlank()) }
    var tab by remember { mutableStateOf(1) }
    var bagItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }

    var name by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var deliveryFee by remember { mutableStateOf("") }
    var priceForAllEnabled by remember { mutableStateOf(true) }
    var priceAll by remember { mutableStateOf("") }
    var cardType by remember { mutableStateOf("classic") }
    var newColor by remember { mutableStateOf("") }

    val colorDrafts = remember { mutableStateListOf<ColorDraft>() }

    fun resetForm() {
        name = ""
        hypothesis = ""
        cost = ""
        photoPath = null
        priceAll = ""
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
            colorDrafts.add(
                ColorDraft(
                    color = value,
                    priceText = if (priceForAllEnabled) "" else priceAll
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

    val canEdit = selectedBagId.isNullOrBlank() || isEditMode

    val hasChanges =
        name.isNotBlank() ||
        hypothesis.isNotBlank() ||
        cost.isNotBlank() ||
        priceAll.isNotBlank() ||
        colorDrafts.isNotEmpty() ||
        !photoPath.isNullOrBlank()

    BackHandler {
        if (canEdit && hasChanges) {
            showExitDialog = true
        } else {
            onDone?.invoke()
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
                    isEditMode = true
                },
                label = { Text("Добавить") }
            )
            FilterChip(
                selected = tab == 1 || selectedBagId != null,
                onClick = { tab = 1 },
                label = { Text("Редактировать") }
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
                                    isEditMode = false
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
                    text = when {
                        selectedBagId.isNullOrBlank() -> "Добавить артикул"
                        isEditMode -> "Редактировать артикул"
                        else -> "Артикул"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!selectedBagId.isNullOrBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isEditMode) {
                            OutlinedButton(
                                onClick = { isEditMode = false }
                            ) {
                                Text("Отмена")
                            }
                        } else {
                            Button(
                                onClick = { isEditMode = true }
                            ) {
                                Text("Редактировать")
                            }
                            OutlinedButton(
                                onClick = {
                                    selectedBagId = null
                                    tab = 1
                                    resetForm()
                                }
                            ) {
                                Text("Назад")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

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
                        enabled = canEdit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Обновить фото")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = canEdit,
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = hypothesis,
                    onValueChange = { hypothesis = it },
                    enabled = canEdit,
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
                        enabled = canEdit,
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
                    enabled = canEdit && priceForAllEnabled,
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
                        enabled = canEdit,
                        label = { Text("Новый цвет") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(onClick = { addColor() }, enabled = canEdit) {
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
                                enabled = canEdit,
                                label = { Text("Цена") },
                                modifier = Modifier.width(140.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        OutlinedButton(
                            onClick = { removeColor(item.color) },
                            enabled = canEdit
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
                        enabled = canEdit,
                        onClick = { cardType = "classic" },
                        label = { Text("Классика") }
                    )
                    FilterChip(
                        selected = cardType == "premium",
                        enabled = canEdit,
                        onClick = { cardType = "premium" },
                        label = { Text("Премиум") }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = cost,
                    onValueChange = { cost = it },
                    enabled = canEdit,
                    label = { Text("Себестоимость") }
                )

                OutlinedTextField(
                    value = deliveryFee,
                    onValueChange = { deliveryFee = it },
                    enabled = canEdit,
                    label = { Text("Доставка") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (canEdit) {
                    Button(
                        onClick = {
                            scope.launch {
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
                                            price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                        )
                                    }
                                )

                                PackUploadManager.saveUserChangesAndUpload(ctx)
                                onDone?.invoke()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedBagId.isNullOrBlank()) "Сохранить" else "Сохранить изменения")
                    }
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
