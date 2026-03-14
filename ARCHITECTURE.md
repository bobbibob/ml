# ML App Architecture

## Overview

Проект состоит из трёх основных частей:

1. Android приложение (Kotlin + Jetpack Compose)
2. Backend на Cloudflare Workers
3. Push уведомления через Firebase Cloud Messaging

Основные модули приложения:

- ML (аналитика продаж и сводки)
- Tasks (задачи и напоминания)
- Push уведомления
- Sync с сервером

---

# Android App

Папка:

app/src/main/java/com/ml/app/

Основные компоненты:

## MainActivity

Отвечает за:

- запуск приложения
- обработку push уведомлений
- передачу `task_id` в UI

Flow:

Push notification → MainActivity → SummaryScreen → TasksScreen

---

## SummaryScreen

Главный экран приложения.

Содержит:

- ML аналитику
- переход к Tasks
- загрузку сводок

---

## TasksScreen

Экран задач.

Функции:

- просмотр задач
- создание задач
- редактирование
- удаление
- обработка открытия задачи по `task_id`

---

# Data Layer

Папки:

data/
repository/

Основные репозитории:

## TasksRepository

Отвечает за:

- создание задач
- обновление
- удаление
- получение задач

---

## SQLiteRepo

Локальная база данных.

Используется для:

- ML сводок
- кэширования данных

---

# Backend (Cloudflare Workers)

Папка:

cloudflare-tasks/

Основные endpoints:

POST /create_task  
GET /my_tasks  
POST /delete_task  
POST /complete_task  

---

# Push Notifications

Используется Firebase Cloud Messaging.

Worker отправляет:

data payload:

{
  type: "task_created",
  task_id: "...",
  open_tasks: "true"
}

Android принимает в:

MlFirebaseMessagingService

---

# Navigation Flow

Push → MainActivity  
MainActivity → SummaryScreen  
SummaryScreen → TasksScreen  
TasksScreen → открытие задачи

---

# Rules for Codex

Codex должен:

1. Работать только в ветке `codex/sandbox`
2. Не менять `stable_build`
3. Делать маленькие коммиты
4. Проверять сборку Android
5. Проверять worker
6. Не ломать ML при изменениях Tasks

---

# CI/CD

GitHub Actions:

codex-android-build  
codex-worker-check  

Запускаются только для:

codex/**

---

# Current Priorities

1. Исправить открытие задачи по push
2. Сделать рабочие reminders
3. Стабилизировать Tasks UI

