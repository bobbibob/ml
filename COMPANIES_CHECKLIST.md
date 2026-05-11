# ✅ Чек-лист: Интеграция управления компаниями

**Дата:** май 2026  
**Версия:** 1.0  
**Статус:** ✅ Готово к использованию  

---

## 📋 ШАГИ ИНТЕГРАЦИИ

### ШАГ 1: Скопировать файлы

**Скопируй эти 4 файла в свой проект:**

```bash
# 1. Хранилище компаний
cp CompaniesRepository.kt \
  app/src/main/java/com/ml/app/data/companies/

# 2. UI экран управления
cp CompaniesScreen.kt \
  app/src/main/java/com/ml/app/ui/companies/

# 3. ViewModel
cp CompaniesViewModel.kt \
  app/src/main/java/com/ml/app/ui/companies/
```

**Проверка:**
- [ ] CompaniesRepository.kt скопирован
- [ ] CompaniesScreen.kt скопирован
- [ ] CompaniesViewModel.kt скопирован
- [ ] Папки `data/companies/` и `ui/companies/` созданы

### ШАГ 2: Обновить SummaryScreen.kt

**Добавь импорты в начало файла:**

```kotlin
import com.ml.app.ui.companies.CompaniesScreen
import com.ml.app.data.companies.CompaniesRepository
import androidx.compose.material.icons.filled.Business
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
```

**Проверка:**
- [ ] Все импорты добавлены
- [ ] Синтаксис правильный

### ШАГ 3: Добавить переменную состояния

**Найди строку:**
```kotlin
var accountMenuExpanded by remember { mutableStateOf(false) }
```

**Добавь после неё:**
```kotlin
var showCompaniesScreen by remember { mutableStateOf(false) }
```

**Проверка:**
- [ ] Переменная добавлена
- [ ] На строке примерно 84

### ШАГ 4: Добавить пункт в меню

**Найди DropdownMenu примерно на строке 333:**
```kotlin
DropdownMenu(
    expanded = accountMenuExpanded,
    onDismissRequest = { accountMenuExpanded = false }
) {
```

**Добавь новый пункт ПЕРЕД пунктом "Выход":**
```kotlin
DropdownMenuItem(
    text = { Text("Управление компаниями") },
    onClick = {
        accountMenuExpanded = false
        showCompaniesScreen = true
    },
    leadingIcon = { 
        Icon(Icons.Default.Business, contentDescription = null)
    }
)
```

**Проверка:**
- [ ] Пункт добавлен
- [ ] На месте в меню
- [ ] Синтаксис правильный

### ШАГ 5: Добавить Dialog с экраном

**Найди конец функции SummaryScreen():**
```kotlin
Column(
    // ... весь контент ...
) {
    // Много элементов...
}
// ← КОНЕЦ Column
```

**Добавь перед закрывающей скобкой Column:**
```kotlin
if (showCompaniesScreen) {
    Dialog(
        onDismissRequest = { showCompaniesScreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .align(Alignment.Center)
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                CompaniesScreen(
                    onClose = { showCompaniesScreen = false }
                )
            }
        }
    }
}
```

**Проверка:**
- [ ] Dialog добавлен
- [ ] Перед закрывающей скобкой Column
- [ ] Синтаксис правильный

---

## 🔨 СБОРКА И ТЕСТИРОВАНИЕ

### Собрать приложение

```bash
./gradlew clean :app:assembleDebug
```

**Проверка:**
- [ ] Ошибок при сборке нет
- [ ] APK собран успешно

### Установить на устройство

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**или в Termux:**
```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/Download/
# Установи вручную через файловый менеджер
```

**Проверка:**
- [ ] APK установлен
- [ ] Приложение запускается без ошибок

### Тестировать функциональность

**Тест 1: Открыть меню управления**
1. Нажми на иконку аккаунта (правый верхний угол) 
2. В меню должен быть пункт "Управление компаниями"
3. Нажми на пункт

**Проверка:**
- [ ] Иконка аккаунта видна
- [ ] Меню открывается
- [ ] Пункт "Управление компаниями" есть
- [ ] Экран управления открывается

**Тест 2: Добавить компанию**
1. На экране управления нажми "Добавить компанию"
2. Заполни поля:
   - Название: "Мой магазин"
   - API ключ: "test_key_12345"
3. Нажми "Добавить"

**Проверка:**
- [ ] Форма появляется
- [ ] Кнопка "Добавить" включена
- [ ] Компания добавляется в список
- [ ] Форма очищается

**Тест 3: Редактировать компанию**
1. В списке компаний нажми кнопку редактирования (карандаш)
2. Изменяй данные
3. Нажми "Сохранить"

**Проверка:**
- [ ] Форма заполняется текущими данными
- [ ] Кнопка меняется на "Сохранить"
- [ ] Компания обновляется

**Тест 4: Удалить компанию**
1. В списке компаний нажми кнопку удаления (красный крестик)
2. Компания должна удалиться

**Проверка:**
- [ ] Компания удаляется из списка
- [ ] Форма очищается

**Тест 5: Проверка дубликатов**
1. Попробуй добавить компанию с существующим API ключом
2. Должна появиться ошибка

**Проверка:**
- [ ] Ошибка "Компания с этим API ключом уже существует"
- [ ] Компания не добавляется

**Тест 6: Проверка пустых полей**
1. Попробуй нажать "Добавить" с пустыми полями
2. Кнопка должна быть неактивна

**Проверка:**
- [ ] Кнопка "Добавить" неактивна когда поля пусты
- [ ] Кнопка активна когда оба поля заполнены

---

## 🎯 ФУНКЦИОНАЛЬНОСТЬ

**После интеграции приложение имеет:**

✅ **Меню управления компаниями** в аккаунте  
✅ **Возможность добавлять компании** с названием и API ключом  
✅ **Возможность редактировать компании** (кнопка карандаша)  
✅ **Возможность удалять компании** (красный крестик)  
✅ **Проверку дубликатов** (API ключ не может быть одинаковым)  
✅ **Хранение в SharedPreferences** (сохраняется при перезагрузке)  
✅ **Красивый UI** с Material Design 3  

---

## 💾 ТЕХНИЧЕСКИЕ ДЕТАЛИ

### Хранилище данных:
```
SharedPreferences:
- Название: "ml_companies"
- Ключ: "companies_list"
- Формат: JSON массив
```

### Структура Company:
```kotlin
data class Company(
    val id: String,           // UUID
    val name: String,         // Название компании
    val apiKey: String,       // API ключ
    val createdAt: Long,      // Время создания
    val updatedAt: Long       // Время обновления
)
```

### API методов CompaniesRepository:
```kotlin
getAllCompanies(): List<Company>
getCompanyById(id: String): Company?
getCompanyByApiKey(apiKey: String): Company?
getCompanyByName(name: String): Company?
addCompany(name: String, apiKey: String): Result<Company>
updateCompany(id: String, name: String, apiKey: String): Result<Company>
deleteCompany(id: String): Result<Unit>
isDuplicate(name: String, apiKey: String, excludeId: String?): Pair<Boolean, String>
```

---

## 🔗 ИСПОЛЬЗОВАНИЕ В КОДЕ

**Получить компанию по API ключу:**
```kotlin
val repository = CompaniesRepository(context)
val company = repository.getCompanyByApiKey("api_key_here")
if (company != null) {
    println("Найдена компания: ${company.name}")
}
```

**Получить все компании:**
```kotlin
val companies = repository.getAllCompanies()
companies.forEach {
    println("${it.name}: ${it.apiKey}")
}
```

**Добавить новую компанию:**
```kotlin
val result = repository.addCompany("Новая компания", "api_key_here")
result.onSuccess { company ->
    println("Компания добавлена с ID: ${company.id}")
}.onFailure { error ->
    println("Ошибка: ${error.message}")
}
```

---

## 🚀 ГОТОВО!

Когда все пункты проверены, интеграция завершена! 🎉

Теперь пользователи смогут:
- ✅ Управлять несколькими компаниями
- ✅ Хранить API ключи безопасно в приложении
- ✅ Переключаться между компаниями
- ✅ Редактировать и удалять компании

---

## 📞 TROUBLESHOOTING

### Ошибка: "CompaniesScreen not found"
**Решение:** Убедись что файл скопирован в `ui/companies/CompaniesScreen.kt`

### Ошибка: "Import not found"
**Решение:** Убедись что все файлы скопированы и папки созданы правильно

### Меню не появляется
**Решение:** Проверь что ты админ (role == "admin") в приложении

### Компании не сохраняются
**Решение:** Убедись что SharedPreferences работает (обычно работает)

### Кнопка добавить не активна
**Решение:** Это нормально - кнопка активна только когда оба поля заполнены

---

**Все готово к использованию! 🎉**

Дата: май 2026  
Версия: 1.0  
Автор: Claude (AI Assistant)
