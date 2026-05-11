// ============================================================================
// КОД ДЛЯ ВСТАВКИ В SummaryScreen.kt
// ============================================================================

// 1. ДОБАВЬ ЭТИ ИМПОРТЫ В НАЧАЛО ФАЙЛА:
// ============================================================================

import com.ml.app.ui.companies.CompaniesScreen
import com.ml.app.data.companies.CompaniesRepository
import androidx.compose.material.icons.filled.Business
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background


// 2. НАЙДИ СТРОКУ ПРИМЕРНО НА СТРОКЕ 84 ГДЕ:
// ============================================================================
// var accountMenuExpanded by remember { mutableStateOf(false) }

// И ДОБАВЬ ПОСЛЕ НЕЁ:
var showCompaniesScreen by remember { mutableStateOf(false) }


// 3. НАЙДИ DropdownMenu ПРИМЕРНО НА СТРОКЕ 333:
// ============================================================================
// DropdownMenu(
//     expanded = accountMenuExpanded,
//     onDismissRequest = { accountMenuExpanded = false }
// ) {

// ВНУТРИ ЭТОГО МЕНЮ, ДОБАВЬ НОВЫЙ ПУНКТ (ПЕРЕД LOGOUT ИЛИ ВЫХОДОМ):

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


// 4. В КОНЦЕ ФУНКЦИИ SummaryScreen() (ПЕРЕД ЗАКРЫВАЮЩЕЙ СКОБКОЙ Column)
// ============================================================================
// ДОБАВЬ ЭТО:

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


// ============================================================================
// ГОТОВЫЙ ПРИМЕР - ПОЛНАЯ ФУНКЦИЯ DropdownMenu
// ============================================================================
/*
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
            draftDisplayName = accountUser.display_name.ifBlank {
              accountUser.email.substringBefore("@").ifBlank { "Пользователь" }
            }
        }
    )
    
    // ← ДОБАВЬ ЗДЕСЬ НОВЫЙ ПУНКТ:
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
            accountMenuExpanded = false
            // существующий код выхода
        }
    )
}
*/


// ============================================================================
// ПРОВЕРКА ПОСЛЕ ИЗМЕНЕНИЙ
// ============================================================================
/*
✅ Добавлены все импорты в начало файла
✅ Добавлена переменная showCompaniesScreen
✅ Добавлен пункт меню "Управление компаниями"
✅ Добавлен Dialog с CompaniesScreen в конце Column
✅ Приложение собирается без ошибок
✅ Меню аккаунта содержит новый пункт
✅ При нажатии на пункт открывается экран управления компаниями
*/


// ============================================================================
// СТРУКТУРА ФАЙЛОВ ПОСЛЕ ДОБАВЛЕНИЯ
// ============================================================================
/*
app/src/main/java/com/ml/app/
├── data/
│   └── companies/
│       └── CompaniesRepository.kt         ← НОВОЕ
│
├── ui/
│   ├── companies/
│   │   ├── CompaniesScreen.kt             ← НОВОЕ
│   │   └── CompaniesViewModel.kt          ← НОВОЕ
│   │
│   └── SummaryScreen.kt                   ← ОБНОВЛЕНО
│
└── MainActivity.kt
*/
