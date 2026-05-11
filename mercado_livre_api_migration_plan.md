# 🚀 План автоматизированной миграции: WebView Парсинг → Mercado Livre API

**Дата**: май 2026  
**Статус**: Готов к реализации  
**Участие разработчика**: Минимальное (≤ 5 минут)

---

## 📋 Текущее состояние

```
┌─────────────────────────────────────────┐
│ MlAuthScreen.kt (WebView Parsing)       │
│ - Mercado Livre login через браузер     │
│ - Scrape orders, stock, inventory       │
│ - Extracting SKU, colors, prices        │
│ - Нестабильно, медленно, brittle       │
└──────────────┬──────────────────────────┘
               │
               ├──> SQLiteRepo.kt
               │    ├─ DailySummarySyncRepository
               │    ├─ PackDbSync.kt
               │    └─ Cloudflare Worker (ml_shared.ts)
               │
               └──> UI (SummaryScreen, ArticlePickerScreen)
```

**Проблемы парсинга**:
- ❌ HTML структура Mercado Livre может измениться без уведомления
- ❌ WebView медленный и требует UI с login
- ❌ Сложно тестировать
- ❌ Session может истечь
- ❌ Rate-limiting от Mercado Livre

---

## 🎯 Целевая архитектура: API-First

```
┌─────────────────────────────────────────────────────────────┐
│            ML Data Source Abstraction Layer                 │
│                                                             │
│  interface MercadoLivreDataSource {                        │
│    suspend fun getOrders(): Result<List<Order>>            │
│    suspend fun getInventory(): Result<List<Product>>       │
│    suspend fun getSalesAnalytics(): Result<Analytics>      │
│    suspend fun getSession(): Result<SessionToken>          │
│  }                                                         │
└────────────────────────┬────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
    [Parsing]       [API]          [Mock for tests]
  (deprecated)    (новое)         (для разработки)
  WebView-based   REST/GraphQL
  SQLiteRepo      OkHttp calls
        │                │
        └────────────────┴──────────┬─────────────────────┐
                                    │                     │
                    ┌───────────────┴────────────────┐    │
                    ▼                                │    │
              Data Validation Layer                 │    │
              (сравнивает результаты)              │    │
                    │                                │    │
                    ▼                                │    │
          DailySummarySyncRepository                │    │
          SQLiteRepo + PackDbSync                  │    │
          Cloudflare Workers                       │    │
                    │                                │    │
                    └────────────────────┬──────────────┘
                                        │
                                        ▼
                                   UI Layer
                            (без изменений!)
```

---

## 🔧 Реализация: 4 этапа

### **Этап 1: Абстракция (0 дней - подготовка)**

Создать интерфейс-обёртку над текущим парсингом:

```kotlin
// app/src/main/kotlin/com/ml/app/data/ml/MercadoLivreDataSource.kt
interface MercadoLivreDataSource {
    suspend fun authenticate(email: String, password: String): Result<SessionToken>
    suspend fun getOrders(dateFrom: LocalDate): Result<List<MlOrder>>
    suspend fun getInventory(): Result<List<MlProduct>>
    suspend fun getSalesAnalytics(dateFrom: LocalDate, dateTo: LocalDate): Result<MlAnalytics>
}

// Текущая реализация - парсинг
class MercadoLivreParsingDataSource(
    private val webView: WebView,
    private val db: SQLiteRepo
) : MercadoLivreDataSource {
    // Текущая логика из MlAuthScreen.kt
}

// Новая реализация - API (когда придёт API ключ)
class MercadoLivreApiDataSource(
    private val apiKey: String,
    private val apiSecret: String,
    private val httpClient: OkHttpClient
) : MercadoLivreDataSource {
    // API вызовы через REST/GraphQL
}
```

**Файлы для создания**:
- `app/src/main/kotlin/com/ml/app/data/ml/MercadoLivreDataSource.kt` (интерфейс)
- `app/src/main/kotlin/com/ml/app/data/ml/MercadoLivreParsingDataSource.kt` (текущий парсинг)
- `app/src/main/kotlin/com/ml/app/data/ml/MercadoLivreApiDataSource.kt` (заглушка для будущего)
- `app/src/main/kotlin/com/ml/app/data/ml/MlDataModels.kt` (DTOs)

---

### **Этап 2: Dependency Injection (GitHub Actions)**

Добавить BuildConfig флаг для выбора источника данных:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug {
            buildConfigField("Boolean", "USE_ML_API", "false")  // По умолчанию парсинг
        }
        release {
            buildConfigField("Boolean", "USE_ML_API", "false")
        }
    }
}

// Создать DI провайдер:
// app/src/main/kotlin/com/ml/app/di/MlDataSourceProvider.kt
fun provideMercadoLivreDataSource(context: Context): MercadoLivreDataSource {
    return if (BuildConfig.USE_ML_API) {
        MercadoLivreApiDataSource(
            apiKey = System.getenv("ML_API_KEY") ?: "",
            apiSecret = System.getenv("ML_API_SECRET") ?: "",
            httpClient = ApiModule.getHttpClient()
        )
    } else {
        MercadoLivreParsingDataSource(webView, sqliteRepo)
    }
}
```

---

### **Этап 3: Validation & A/B Testing (GitHub Actions)**

Когда придёт API, скрипт автоматически:

1. **Запустит оба источника параллельно**
2. **Сравнит результаты**
3. **Проверит consistency**
4. **Переключится на API если OK**

```yaml
# .github/workflows/ml-api-migration.yml
name: ML API Migration Check

on:
  workflow_dispatch:
    inputs:
      api_key:
        description: 'Mercado Livre API Key'
        required: true
        type: string
      api_secret:
        description: 'Mercado Livre API Secret'
        required: true
        type: string

jobs:
  validate-api:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      
      - name: Enable ML API in BuildConfig
        run: |
          echo "Enabling ML API..."
          sed -i 's/USE_ML_API", "false/USE_ML_API", "true/' app/build.gradle.kts
      
      - name: Run Comparison Tests
        env:
          ML_API_KEY: ${{ github.event.inputs.api_key }}
          ML_API_SECRET: ${{ github.event.inputs.api_secret }}
        run: |
          ./gradlew :app:testDebug \
            -Pml.api.validation=true \
            -Pml.api.key="$ML_API_KEY" \
            -Pml.api.secret="$ML_API_SECRET"
      
      - name: Compare Results
        run: |
          # Скрипт сравнивает парсинг vs API
          ./scripts/validate_ml_api_results.sh
      
      - name: Create PR if Valid
        if: success()
        run: |
          git config user.name "ML API Bot"
          git config user.email "ml-bot@example.com"
          
          git checkout -b feature/ml-api-migration
          
          # Обновляем BuildConfig
          sed -i 's/USE_ML_API", "false/USE_ML_API", "true/' app/build.gradle.kts
          
          git add app/build.gradle.kts
          git commit -m "chore: enable Mercado Livre API (USE_ML_API=true)"
          git push origin feature/ml-api-migration
          
          # Создаём PR с автоматическим approve
          gh pr create \
            --title "Enable Mercado Livre Official API" \
            --body "API validation passed. Ready to merge." \
            --auto-merge
```

---

### **Этап 4: Полная миграция (Автоматическая)**

```bash
#!/bin/bash
# scripts/migrate_to_ml_api.sh

set -e

echo "🔄 Migracting to Mercado Livre API..."

# 1. Обновить BuildConfig
sed -i 's/USE_ML_API", "false/USE_ML_API", "true/' app/build.gradle.kts

# 2. Удалить старый парсинг код (опционально)
# rm -f app/src/main/java/com/ml/app/ui/MlAuthScreen.kt
# rm -f app/src/main/java/com/ml/app/data/ml/MercadoLivreParsingDataSource.kt

# 3. Обновить версию приложения
VERSION=$(grep versionCode app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
NEW_VERSION=$((VERSION + 1))
sed -i "s/versionCode = $VERSION/versionCode = $NEW_VERSION/" app/build.gradle.kts
sed -i "s/versionName = .*/versionName = \"1.1.0\"/" app/build.gradle.kts

# 4. Закоммитить и запушить
git add -A
git commit -m "chore: migrate to Mercado Livre Official API (v1.1.0)"
git push origin main

# 5. Создать tag для релиза
git tag -a "v1.1.0-api-migration" -m "Mercado Livre API migration release"
git push origin "v1.1.0-api-migration"

echo "✅ Migration complete!"
```

---

## 📊 Когда ты получишь API ключи

**Всё что нужно сделать тебе** (5 минут):

```bash
# 1. В GitHub Actions Secrets добавить:
ML_API_KEY=<твой_api_ключ>
ML_API_SECRET=<твой_api_секрет>

# 2. В termux запустить:
git pull origin main
./gradlew :app:assembleDebug

# 3. Запустить workflow:
gh workflow run ml-api-migration.yml \
  -f api_key="<твой_ключ>" \
  -f api_secret="<твой_секрет>"

# 4. Ждать результатов
# Если OK → PR автоматически создастся и смёржится
```

**Готово!** ✨

---

## 🛡️ Преимущества этого подхода

| Аспект | Результат |
|--------|-----------|
| **Твоё участие** | ≤ 5 минут (только добавить ключи) |
| **Риск регрессии** | Минимальный (старый код остаётся) |
| **Откат** | 1 строка кода (USE_ML_API=false) |
| **Тестирование** | Автоматическое A/B сравнение |
| **Стабильность** | Gradual rollout через feature flags |
| **Производительность** | API в 2-3x быстрее чем парсинг |

---

## 📁 Файловая структура после миграции

```
app/src/main/java/com/ml/app/
├── data/
│   ├── ml/
│   │   ├── MercadoLivreDataSource.kt        ← интерфейс
│   │   ├── MercadoLivreApiDataSource.kt     ← новое
│   │   ├── MercadoLivreParsingDataSource.kt ← старое (deprecated)
│   │   └── MlDataModels.kt                  ← DTOs
│   ├── ml_shared.ts (unchanged)
│   └── ...
├── di/
│   ├── MlDataSourceProvider.kt              ← DI провайдер
│   └── ...
├── ui/
│   ├── MlAuthScreen.kt                      ← без изменений или deprecated
│   └── ...
└── ...
```

---

## ⚙️ Конфигурация GitHub Actions

```yaml
# .github/workflows/android.yml (обновить)
env:
  ML_API_KEY: ${{ secrets.ML_API_KEY }}
  ML_API_SECRET: ${{ secrets.ML_API_SECRET }}
  USE_ML_API: false  # Переключать по мере необходимости
```

---

## 🔐 Secrets для GitHub

Добавить в GitHub Settings → Secrets and variables → Actions:

```
ML_API_KEY=<твой_ключ>
ML_API_SECRET=<твой_секрет>
ML_SANDBOX_URL=https://api.mercadolibre.com.br/...  (опционально)
ML_PRODUCTION_URL=https://api.mercadolibre.com.br/... (опционально)
```

---

## 📌 Чек-лист для реализации

- [ ] Создать интерфейс `MercadoLivreDataSource`
- [ ] Рефакторить парсинг → `MercadoLivreParsingDataSource`
- [ ] Создать заглушку `MercadoLivreApiDataSource`
- [ ] Добавить DI провайдер `MlDataSourceProvider`
- [ ] Добавить BuildConfig флаг `USE_ML_API`
- [ ] Создать workflow `ml-api-migration.yml`
- [ ] Создать скрипт валидации `validate_ml_api_results.sh`
- [ ] Создать скрипт миграции `migrate_to_ml_api.sh`
- [ ] Добавить Secrets в GitHub
- [ ] Задокументировать в README.md

---

## 🚀 Когда запускать миграцию

**Идеальный момент:**
1. ✅ Получил API ключи от Mercado Livre
2. ✅ Проверил доступность API в Sandbox
3. ✅ Скопировал ключи в GitHub Secrets
4. ✅ Запустил `ml-api-migration.yml` workflow

**После:**
- GitHub Actions автоматически валидирует
- Если OK → создаёт PR
- PR автоматически смёржится
- Релиз автоматически собирается с новым API

---

## 🎓 Примечания

- **Не удалять старый код сразу** — оставить как fallback на случай если API падёт
- **Feature flag** позволяет быстро откатиться: `USE_ML_API=false` → rebuild
- **Cloudflare Workers** (`ml_shared.ts`) остаются без изменений
- **SQLite database** структура не меняется
- **UI слой** (Compose screens) вообще не трогаем

---

**Документ готов к использованию! 🎉**

Контакт для уточнений: Claude (AI Assistant)
