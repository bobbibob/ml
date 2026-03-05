#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"
test -f "$FILE"

python3 - <<'PY'
from pathlib import Path

path = Path("app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt")
src = path.read_text(encoding="utf-8")

patched = False

# 1) Удаляем дубликат "Цена продажи" (обычно это OutlinedTextField с label/placeholder Text("Цена продажи"))
#    Делаем аккуратно: находим строку с Text("Цена продажи"), затем вырезаем ближайший вызов OutlinedTextField(...) / TextField(...)
lines = src.splitlines(True)

def remove_textfield_block_containing(needle: str):
    global patched
    idx = None
    for i, ln in enumerate(lines):
        if needle in ln:
            idx = i
            break
    if idx is None:
        return False

    # ищем вверх начало блока TextField/OutlinedTextField
    start = idx
    while start > 0 and ("OutlinedTextField(" not in lines[start] and "TextField(" not in lines[start]):
        start -= 1
    if start == 0 and ("OutlinedTextField(" not in lines[start] and "TextField(" not in lines[start]):
        return False

    # теперь вниз до закрытия скобок вызова
    # считаем круглые скобки с позиции start
    par = 0
    end = start
    started = False
    for j in range(start, len(lines)):
        par += lines[j].count("(")
        par -= lines[j].count(")")
        if "OutlinedTextField(" in lines[j] or "TextField(" in lines[j]:
            started = True
        if started and par <= 0:
            end = j
            break

    # также съедаем возможный Spacer сразу после
    end2 = end
    if end2 + 1 < len(lines) and "Spacer(" in lines[end2 + 1]:
        end2 += 1

    del lines[start:end2+1]
    patched = True
    return True

remove_textfield_block_containing('Text("Цена продажи")')

# 2) Добавляем UI списка цветов с ценами рядом.
#    Вставляем сразу ПОСЛЕ строки/блока, где есть поле "Новый цвет" (рядом кнопка "Добавить")
#    Ищем первое появление Text("Новый цвет") и вставляем после ближайшего закрывающего '}' строки Row/блока.
src2 = "".join(lines)

needle = 'Text("Новый цвет")'
pos = src2.find(needle)
if pos != -1:
    # найдём позицию вставки: после следующего "\n}" на том же/меньшем отступе
    # Простейший вариант: вставим после первой закрывающей фигурной скобки '}' которая идёт ПОСЛЕ needle и на новой строке.
    after = src2.find("\n", pos)
    insert_at = None
    # пройдёмся вперёд по строкам
    rest = src2[after:]
    rest_lines = rest.splitlines(True)

    # попробуем найти первую строку, которая выглядит как конец Row { ... } (закрывающая скобка)
    for k, ln in enumerate(rest_lines):
        stripped = ln.strip()
        if stripped == "}":
            # вставим ПОСЛЕ этой строки
            insert_at = after + sum(len(x) for x in rest_lines[:k+1])
            break

    if insert_at is not None and "/* ML_COLOR_LIST_V1 */" not in src2:
        block = r'''
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
'''
        src2 = src2[:insert_at] + block + src2[insert_at:]
        patched = True

# 3) Гарантируем, что нужные state-переменные существуют.
#    Мы ожидаем, что в файле уже есть:
#    - colors: MutableList/StateList<String>
#    - priceAll: String state
#    - checkbox (цена для всех)
#    Если названия отличаются — добавим мягкую совместимость:
#    создадим alias-переменные ТОЛЬКО если их нет.
if "val colorPrices =" not in src2:
    # вставим рядом с другими remember{} переменными (попробуем после первого rememberSaveable/remember)
    anchor = "remember"
    i = src2.find(anchor)
    if i != -1:
        insert = "\n// per-color prices\nval colorPrices = remember { mutableStateMapOf<String, String>() }\n"
        src2 = src2[:i] + insert + src2[i:]
        patched = True

# alias-флаги: если в твоём файле флаг называется иначе — не мешаем.
# Но наш UI использует priceForAllEnabled и priceAll. Добавим alias если не найдено.
if "priceForAllEnabled" not in src2:
    # Попробуем найти существующий boolean стейт по тексту "Цена для всех цветов"
    # Если не получится — добавим простой alias на существующий 'priceForAll' если есть.
    if "priceForAll" in src2 and "val priceForAllEnabled" not in src2:
        src2 = src2.replace("priceForAll", "priceForAllEnabled", 1)
        patched = True

# 4) Импорты: добавим нужные импорты только если отсутствуют.
def ensure_import(imp: str):
    global src2, patched
    if imp in src2:
        return
    # вставим после package ... первой группой импортов
    p = src2.find("\nimport ")
    if p == -1:
        # если импортов нет — после package
        p2 = src2.find("\n")
        if p2 != -1:
            src2 = src2[:p2+1] + f"import {imp}\n" + src2[p2+1:]
            patched = True
        return
    src2 = src2[:p+1] + f"import {imp}\n" + src2[p+1:]
    patched = True

# Эти импорты часто уже есть, но на всякий:
ensure_import("androidx.compose.runtime.mutableStateMapOf")
ensure_import("androidx.compose.ui.Alignment")
ensure_import("androidx.compose.ui.text.style.TextOverflow")

# 5) Если мы вставили block, но TextOverflow/Alignment используем с полным квалифайером — imports не критичны, но не мешают.

path.write_text(src2, encoding="utf-8")
print("patched:", patched)
PY

echo "DONE. Now:"
echo "  git status"
echo "  git add $FILE"
echo "  git commit -m \"UI: colors list with per-color prices (variant A)\""
echo "  git push"
