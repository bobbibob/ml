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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.SQLiteRepo.BagPickerRow
import com.ml.app.data.SQLiteRepo.BagColorPriceRow
import java.io.File
import java.util.UUID

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
    var tab by remember { mutableStateOf(if (bagId.isNullOrBlank()) 0 else 1) }
    var bagItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }

    var name by remember { mutableStateOf("") }
    var hypothesis by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
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

    BackHandler {
        showExitDialog = true
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

        val savedPrices = kotlin.runCatching { repo.getBagColorPrices(id) }.getOrDefault(emptyList())
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
                    label = { Text("Себестоимость") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val id = selectedBagId
                        if (!id.isNullOrBlank()) {
                            kotlin.runCatching {
                                repo.replaceBagColorPrices(
                                    id,
                                    colorDrafts.map {
                                        BagColorPriceRow(
                                            color = it.color,
                                            price = if (priceForAllEnabled) null else it.priceText.replace(",", ".").toDoubleOrNull()
                                        )
                                    }
                                )
                            }
                        }
                        showExitDialog = true
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
