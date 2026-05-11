# 🏢 Интеграция управления компаниями в SummaryScreen

**Добавляем пункт меню управления компаниями в AccountMenu**

---

## 📋 Шаг 1: Скопируй файлы

```bash
# Скопируй в проект:
cp CompaniesRepository.kt app/src/main/java/com/ml/app/data/companies/
cp CompaniesScreen.kt app/src/main/java/com/ml/app/ui/companies/
cp CompaniesViewModel.kt app/src/main/java/com/ml/app/ui/companies/
```

## 🔧 Шаг 2: Обнови SummaryScreen.kt

### Добавь import'ы в начало файла:

```kotlin
import com.ml.app.ui.companies.CompaniesScreen
import com.ml.app.ui.companies.CompaniesDropdownMenuItem
import com.ml.app.data.companies.CompaniesRepository
```

### Найди где определяется `accountMenuExpanded`:

**Текущий код:**
```kotlin
var accountMenuExpanded by remember { mutableStateOf(false) }
```

**Обнови на:**
```kotlin
var accountMenuExpanded by remember { mutableStateOf(false) }
var showCompaniesScreen by remember { mutableStateOf(false) }
```

### Найди DropdownMenu с пунктами меню (там где Profile, Logout и т.д.)

**Текущий код примерно выглядит так:**
```kotlin
DropdownMenu(
    expanded = accountMenuExpanded,
    onDismissRequest = { accountMenuExpanded = false }
) {
    DropdownMenuItem(
        text = { Text(accountUser.email) },
        onClick = { }
    )
    DropdownMenuItem(
        text = { Text("Профиль") },
        onClick = {
            accountMenuExpanded = false
            draftDisplayName = ...
        }
    )
    // Другие пункты...
}
```

**Добавь новый пункт меню прямо перед Logout:**
```kotlin
DropdownMenuItem(
    text = { Text("Управление компаниями") },
    onClick = {
        accountMenuExpanded = false
        showCompaniesScreen = true
    },
    leadingIcon = { 
        Icon(
            imageVector = Icons.Default.Business,
            contentDescription = null
        )
    }
)
```

### Добавь иконку Business в imports:

```kotlin
import androidx.compose.material.icons.filled.Business
```

## 🎨 Шаг 3: Добавь диалог/экран компаний

Найди конец функции `SummaryScreen()` (примерно в конце Column) и добавь:

```kotlin
// Экран управления компаниями
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

### Добавь необходимые imports для Dialog:

```kotlin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
```

## ✅ Готово!

Теперь в меню аккаунта появится пункт "Управление компаниями"

---

## 📝 Полный пример изменений в SummaryScreen

```kotlin
// ДО (примерно строка 84)
var accountMenuExpanded by remember { mutableStateOf(false) }

// ПОСЛЕ
var accountMenuExpanded by remember { mutableStateOf(false) }
var showCompaniesScreen by remember { mutableStateOf(false) }
```

```kotlin
// В DropdownMenu добавить пункт:
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

DropdownMenuItem(
    text = { Text("Выход") },
    onClick = {
        // существующий код выхода
    }
)
```

```kotlin
// В конце Column добавить экран компаний:
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

---

## 🧪 Тестирование

1. Собери приложение: `./gradlew :app:assembleDebug`
2. Установи APK на устройство
3. Нажми на иконку аккаунта (правый верхний угол)
4. В меню должен быть пункт "Управление компаниями"
5. При нажатии откроется экран управления

---

## 🎯 Функциональность

### Экран управления компаниями содержит:

✅ **Список добавленных компаний** с кнопками:
   - 📝 Редактировать (карандаш)
   - 🗑️ Удалить (красный крестик)

✅ **Форма добавления новой компании**:
   - Поле "Название компании"
   - Поле "API ключ"
   - Кнопка "Добавить компанию" (включена только если оба поля заполнены)

✅ **Редактирование компании**:
   - При клике на карандаш форма заполняется данными компании
   - Кнопка меняется на "Сохранить"

✅ **Удаление компании**:
   - При клике на красный крестик компания удаляется

✅ **Проверка дубликатов**:
   - Нельзя добавить компанию с существующим API ключом
   - Нельзя добавить компанию с существующим названием
   - При попытке добавить дубликат - ошибка "Компания с этим API ключом уже существует"

---

## 💾 Где хранятся данные?

Все компании сохраняются в **SharedPreferences**:
- **Хранилище**: `ml_companies`
- **Ключ**: `companies_list`
- **Формат**: JSON массив

Данные сохраняются автоматически при каждом изменении.

---

## 🔗 Интеграция с API

После добавления компании ты сможешь получить её API ключ:

```kotlin
val repository = CompaniesRepository(context)
val company = repository.getCompanyByName("Мой магазин")
if (company != null) {
    val apiKey = company.apiKey
    // Используй API ключ для подключения к Mercado Livre API
}
```

---

## 🚀 Готово!

Теперь приложение полностью готово к управлению несколькими компаниями с их API ключами! 🎉
