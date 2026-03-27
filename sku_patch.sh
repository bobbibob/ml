#!/bin/bash
set -e

echo "1. Добавляем таблицу в SQLiteRepo..."

FILE="app/src/main/java/com/ml/app/data/SQLiteRepo.kt"

grep -q "card_color_sku" "$FILE" || sed -i '/CREATE TABLE IF NOT EXISTS bag_stock_override/a\
\
CREATE TABLE IF NOT EXISTS card_color_sku (\
  card_name TEXT NOT NULL,\
  color TEXT NOT NULL,\
  sku TEXT NOT NULL,\
  article_id TEXT NOT NULL,\
  PRIMARY KEY(card_name, color)\
);' "$FILE"


echo "2. Добавляем методы в SQLiteRepo..."

grep -q "fun getAllSkus()" "$FILE" || cat >> "$FILE" <<'KOTLIN'

fun getAllSkus(): List<String> {
    return listOf(
"A21189-1","A21189-2","A21189-3","A21189-4","A21189-5","A21189-6","A21189-7",
"A21190-1","A21190-2","A21190-3","A21190-4","A21190-5","A21190-6","A21190-7",
"A21192-1","A21192-2","A21192-3","A21192-4","A21192-5","A21192-6","A21192-7",
"BL20457-1","BL20457-2","BL20457-3","BL20457-4","BL20457-5","BL20457-6","BL20457-7",
"HB50171-1","HB50171-2","HB50171-3","HB50171-4","HB50171-5","HB50171-6","HB50171-7",
"HB50173-1","HB50173-2","HB50173-3","HB50173-4","HB50173-5","HB50173-6","HB50173-7",
"HB50173-8","HB50173-10","HB50173-11",
"MB80002-1","MB80002-2","MB80002-3","MB80002-4","MB80002-5","MB80002-6",
"VJ001V","sumka10-1","sumka10-2","sumka10-3","sumka11-1","sumka11-2","sumka11-3",
"sumka8-1","sumka8-2","sumka9-1","sumka9-2","sumka9-3","sumka9-4","sumka9-5","sumka9-6","sumka9-8","w02"
    )
}

fun extractArticleId(sku: String): String {
    return sku.replace(Regex("-\\d+$"), "")
}

fun getSkuFor(card: String, color: String): String? {
    val db = readableDatabase
    val c = db.rawQuery(
        "SELECT sku FROM card_color_sku WHERE card_name=? AND color=?",
        arrayOf(card, color)
    )
    val res = if (c.moveToFirst()) c.getString(0) else null
    c.close()
    return res
}

fun setSkuFor(card: String, color: String, sku: String) {
    val db = writableDatabase
    db.execSQL(
        "INSERT OR REPLACE INTO card_color_sku(card_name,color,sku,article_id) VALUES(?,?,?,?)",
        arrayOf(card, color, sku, extractArticleId(sku))
    )
}
KOTLIN


echo "3. Патчим AddEditArticleScreen..."

UI="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

# режим редактирования
grep -q "var isEditMode" "$UI" || sed -i '/var selectedBagId/a\
\
    var isEditMode by remember { mutableStateOf(false) }\
    var originalState by remember { mutableStateOf("") }' "$UI"

# сохраняем исходное состояние
grep -q "originalState =" "$UI" || sed -i '/loadBagFromPicker/a\
        originalState = name + "|" + hypothesis + "|" + priceAll + "|" + cost' "$UI"

# кнопка редактировать
grep -q "Редактировать" "$UI" || sed -i '/Text("Редактировать артикул")/a\
\
                Button(onClick = { isEditMode = true }) {\
                    Text("Редактировать")\
                }' "$UI"

# кнопка отмена
grep -q "Отмена" "$UI" || sed -i '/Сохранить изменения/a\
\
                OutlinedButton(onClick = {\
                    isEditMode = false\
                }) { Text("Отмена") }' "$UI"

# блок SKU выбора
grep -q "SKU" "$UI" || sed -i '/for (i in colorDrafts.indices)/i\
\
                val allSkus = repo.getAllSkus()' "$UI"

grep -q "DropdownMenu" "$UI" || sed -i '/Text(text = item.color/a\
\
                          if (isEditMode) {\
                              var expanded by remember { mutableStateOf(false) }\
                              val currentSku = repo.getSkuFor(name, item.color)\
\
                              Column {\
                                  OutlinedButton(onClick = { expanded = true }) {\
                                      Text(currentSku ?: "Выбрать SKU")\
                                  }\
                                  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {\
                                      allSkus.forEach { sku ->\
                                          DropdownMenuItem(text = { Text(sku) }, onClick = {\
                                              repo.setSkuFor(name, item.color, sku)\
                                              expanded = false\
                                          })\
                                      }\
                                  }\
                              }\
                          } else {\
                              val sku = repo.getSkuFor(name, item.color)\
                              if (sku != null) Text("SKU: $sku")\
                          }' "$UI"

echo "ГОТОВО"
