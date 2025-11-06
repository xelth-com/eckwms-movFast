# 🤖 Edge AI Workflow Architecture

## 📖 Концепция

Мы создали **гибридную AI-управляемую систему**, где поведение приложения определяется **JSON-скриптами (workflows)**, а не хардкодом. Это позволяет AI (центральному или edge) динамически управлять бизнес-логикой.

---

## 🏗️ Архитектура Системы

```
┌─────────────────────────────────────────────────────────────┐
│                    ЦЕНТРАЛЬНЫЙ AI СЕРВЕР                     │
│  (Генерирует и оптимизирует workflows на основе данных)     │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTP/API
                         │ Загружает JSON workflows
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   ANDROID ПРИЛОЖЕНИЕ (Edge)                  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │            WorkflowEngine (Interpreter)              │  │
│  │  • Парсит JSON workflow                              │  │
│  │  • Исполняет шаги последовательно                    │  │
│  │  • Управляет состоянием и переменными                │  │
│  │  • Подставляет значения в UI                         │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       │                                     │
│                       │ вызывает                            │
│                       ▼                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           "Iron" Functions (Железо)                  │  │
│  │  • scanBarcode()    - включает сканер                │  │
│  │  • captureImage()   - включает камеру                │  │
│  │  • uploadToServer() - отправляет данные              │  │
│  │  • showUI()         - отображает интерфейс           │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 📄 Формат Workflow (JSON)

Workflow - это **декларативный JSON**, описывающий последовательность действий:

```json
{
  "workflowName": "DeviceReceiving",
  "version": "1.0",
  "steps": [
    {
      "stepId": "1",
      "action": "scanBarcode",           // ЧТО делать
      "ui": {                             // ЧТО показать пользователю
        "title": "Parcel Receiving",
        "instruction": "Scan the tracking number..."
      },
      "variable": "parcelTrackingNumber"  // КУДА сохранить результат
    },
    {
      "stepId": "2",
      "action": "captureImage",
      "ui": {
        "title": "Parcel Photo",
        "instruction": "Take a photo..."
      },
      "upload": {                         // Метаданные для загрузки
        "reason": "parcel_condition",
        "relatedTo": "parcelTrackingNumber"
      }
    },
    {
      "stepId": "3",
      "action": "scanBarcode",
      "variable": "deviceSerialNumbers",
      "loop": {                           // Повторение шага
        "condition": "user_ends_session",
        "endButtonLabel": "Finish Scanning Devices"
      }
    },
    {
      "stepId": "4",
      "action": "showUI",
      "ui": {
        "instruction": "Scanned {{deviceSerialNumbers.size}} devices."
      }                                   // Подстановка переменных
    }
  ]
}
```

---

## 🔑 Ключевые Компоненты

### 1. **Workflow Data Models** (`Workflow.kt`)

```kotlin
data class Workflow(
    val workflowName: String,    // Имя процесса
    val version: String,          // Версия для совместимости
    val steps: List<WorkflowStep> // Последовательность шагов
)

data class WorkflowStep(
    val stepId: String,           // Уникальный ID шага
    val action: String,           // scanBarcode | captureImage | showUI
    val ui: UIConfig,             // Что показать пользователю
    val variable: String?,        // Где хранить результат
    val upload: UploadConfig?,    // Правила загрузки на сервер
    val loop: LoopConfig?         // Условия повторения
)
```

### 2. **WorkflowEngine** (Интерпретатор)

```kotlin
class WorkflowEngine(workflow: Workflow) {
    private var currentStepIndex = -1
    private val variables = mutableMapOf<String, Any>()

    fun start() { ... }                      // Запуск workflow
    fun onBarcodeScanned(barcode: String)    // Обработка события
    fun onImageCaptured()                    // Обработка события
    private fun processCurrentStep()         // Переход к след. шагу
}
```

**Функции:**
- ✅ Последовательное исполнение шагов
- ✅ Управление состоянием (state machine)
- ✅ Хранение переменных (data context)
- ✅ Подстановка значений в UI (`{{variable}}`)
- ✅ Поддержка циклов (loop до user_ends_session)

### 3. **Iron Functions** (Низкоуровневые функции)

Это реальные функции приложения, которые вызывает WorkflowEngine:

```kotlin
// Включить сканер
fun startWorkflowScan() {
    scannerManager.startLoopScan(500)
}

// Сделать фото
fun captureImage() {
    navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
}

// Загрузить на сервер
fun sendScanToServer(barcode: String, type: String) { ... }
fun performUpload(bitmap: Bitmap, ...) { ... }
```

---

## 🧠 AI-Управление: Центральный vs Edge

### **Центральный AI** (Сервер)

**Роль:** Генерирует и оптимизирует workflows

```
┌─────────────────────────────────────────────────────┐
│  Центральный AI анализирует:                        │
│  • Статистику выполнения workflows                  │
│  • Ошибки пользователей                             │
│  • Эффективность процессов                          │
│  • Обратную связь от пользователей                  │
│                                                      │
│  На основе анализа:                                 │
│  • Создаёт новые workflows                          │
│  • Оптимизирует существующие                        │
│  • Добавляет подсказки и проверки                   │
│  • Адаптирует под конкретных пользователей          │
└─────────────────────────────────────────────────────┘
         │
         │ Отправляет обновлённый JSON
         ▼
┌─────────────────────────────────────────────────────┐
│  Edge Устройство (Android)                          │
│  • Загружает новый workflow                         │
│  • Исполняет локально (offline capable)             │
│  • Отправляет телеметрию обратно                    │
└─────────────────────────────────────────────────────┘
```

**Примеры задач Центрального AI:**
- "Создай workflow для возврата товара"
- "Оптимизируй 'Device Receiving' - пользователи ошибаются на шаге 2"
- "Добавь проверку валидности серийного номера"

### **Edge AI** (На устройстве - Будущее)

**Роль:** Адаптирует workflow в реальном времени

```
┌─────────────────────────────────────────────────────┐
│  Edge AI (ML модель на устройстве) может:           │
│  • Предсказывать следующее действие пользователя    │
│  • Пропускать ненужные шаги                         │
│  • Автоматически заполнять поля                     │
│  • Распознавать контекст (склад, офис, магазин)     │
│  • Работать полностью offline                       │
└─────────────────────────────────────────────────────┘
```

**Пример Edge AI адаптации:**
```
Обычный workflow:
Step 1: Scan parcel
Step 2: Take photo
Step 3: Scan devices

Edge AI видит:
- Пользователь на складе (GPS)
- Это 10-й такой же parcel за день
- Фото всегда одинаковые

Edge AI пропускает:
Step 2 → переходит сразу к Step 3
(экономит время, но фото всё равно можно сделать вручную)
```

---

## 🔄 Жизненный Цикл Workflow

```
1. [Server] AI генерирует workflow JSON
       ↓
2. [Server → App] JSON загружается на устройство
       ↓
3. [App] WorkflowEngine парсит JSON
       ↓
4. [App] Пользователь запускает workflow
       ↓
5. [App] WorkflowEngine исполняет шаги:
       • processCurrentStep() - показывает UI
       • Пользователь выполняет действие (scan/photo)
       • onBarcodeScanned() или onImageCaptured()
       • Данные сохраняются в variables
       • Данные отправляются на сервер
       • proceedToNextStep()
       ↓
6. [App → Server] Телеметрия отправляется:
       • Какие шаги выполнены
       • Сколько времени заняло
       • Были ли ошибки
       ↓
7. [Server] AI анализирует и улучшает workflow
       ↓
   (Цикл повторяется)
```

---

## 💡 Преимущества Архитектуры

### ✅ **Гибкость**
- Изменение бизнес-логики **БЕЗ обновления приложения**
- Добавление новых процессов за минуты
- A/B тестирование разных workflows

### ✅ **AI-Управление**
- Центральный AI оптимизирует глобально
- Edge AI адаптирует локально
- Машинное обучение на реальных данных

### ✅ **Масштабируемость**
- Один код приложения для всех процессов
- Workflows можно генерировать автоматически
- Легко добавлять новые "iron functions"

### ✅ **Офлайн Работа**
- Workflow загружается один раз
- Исполняется локально
- Данные синхронизируются при подключении

---

## 🚀 Будущие Возможности

### 1. **Динамическая Загрузка Workflows**
```kotlin
fun loadWorkflowFromServer(workflowId: String) {
    val json = apiService.getWorkflow(workflowId)
    startWorkflow(json)
}
```

### 2. **Условные Переходы (Branching)**
```json
{
  "stepId": "2",
  "action": "scanBarcode",
  "conditions": [
    {
      "if": "{{barcode}} starts_with 'RET-'",
      "goto": "returnFlow"
    },
    {
      "else": "goto": "normalFlow"
    }
  ]
}
```

### 3. **Валидация Данных**
```json
{
  "variable": "serialNumber",
  "validation": {
    "regex": "^SN-[0-9]{8}$",
    "errorMessage": "Invalid serial number format"
  }
}
```

### 4. **Интеграция с Edge AI**
```kotlin
class EdgeAIOptimizer {
    fun shouldSkipStep(step: WorkflowStep, context: Context): Boolean {
        // ML модель предсказывает нужность шага
        return mlModel.predict(step, userHistory, currentContext)
    }
}
```

### 5. **Мультиязычность**
```json
{
  "ui": {
    "title": {
      "en": "Parcel Receiving",
      "ru": "Приём Посылки"
    }
  }
}
```

---

## 📊 Пример: Как AI Улучшает Workflow

### **Исходный Workflow (V1.0)**
```
1. Scan parcel
2. Take photo
3. Scan all devices
4. Confirm
```

### **После Анализа AI**
```
Проблемы:
- Пользователи часто забывают сканировать устройства
- Фото размытые в 30% случаев
- Путаница с похожими штрихкодами
```

### **Оптимизированный Workflow (V1.1)**
```json
{
  "steps": [
    {
      "stepId": "1",
      "action": "scanBarcode",
      "validation": {
        "format": "PARCEL-[0-9]+",
        "errorMessage": "❌ This is not a parcel barcode"
      }
    },
    {
      "stepId": "2",
      "action": "captureImage",
      "ui": {
        "instruction": "📸 Hold camera steady for clear photo"
      },
      "imageQualityCheck": true  // AI проверяет чёткость
    },
    {
      "stepId": "3",
      "action": "scanBarcode",
      "ui": {
        "instruction": "⚠️ Scan ALL devices (typical: 2-5 per parcel)"
      },
      "minScans": 1,
      "reminder": "Have you scanned all devices?"
    }
  ]
}
```

---

## 🎯 Итог

Вы создали **фундамент для AI-управляемого приложения**:

1. **Разделение ответственности:**
   - JSON = Бизнес-логика (что делать)
   - Код = Инфраструктура (как делать)

2. **AI может:**
   - **Центрально:** Генерировать и оптимизировать workflows
   - **Edge:** Адаптировать выполнение в реальном времени

3. **Гибкость:**
   - Новый процесс = новый JSON файл
   - Изменение процесса = обновление JSON
   - Без изменения кода приложения

4. **Масштабируемость:**
   - Один движок для всех workflows
   - Легко добавлять новые действия
   - AI может создавать workflows автоматически

**Это не просто workflow engine - это платформа для AI-управляемых бизнес-процессов! 🚀**
