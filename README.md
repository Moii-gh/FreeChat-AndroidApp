<h1 align="center">FreeChat</h1>

<p align="center">
  <img src="app/src/main/res/drawable/logo.png" alt="FreeChat logo" width="140" />
</p>

<p align="center">
  <strong>Android AI-чат с локальной историей, Telegram/VK-авторизацией, генерацией изображений и Node.js backend.</strong>
</p>

<p align="center">
  <a href="#github-description">GitHub Description</a> |
  <a href="#overview">Обзор</a> |
  <a href="#features">Возможности</a> |
  <a href="#architecture">Архитектура</a> |
  <a href="#quick-start">Быстрый старт</a> |
  <a href="#api">API</a> |
  <a href="#tests">Тесты</a>
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-34-1f7a4d?style=for-the-badge&logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-2f4f9d?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack_Compose-UI-0d9488?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img alt="Node.js" src="https://img.shields.io/badge/Node.js-Backend-22543d?style=for-the-badge&logo=nodedotjs&logoColor=white" />
  <img alt="Express" src="https://img.shields.io/badge/Express-API-222222?style=for-the-badge&logo=express&logoColor=white" />
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-Data-1d4ed8?style=for-the-badge&logo=postgresql&logoColor=white" />
</p>

---

<a id="github-description"></a>

## Описание для GitHub

Android AI chat with Telegram/VK auth, local-first history, image and file tools, screen assistant, shared chats, and a Node.js/PostgreSQL backend.

<a id="overview"></a>

## Обзор

**FreeChat** - мобильное AI-приложение для Android. Клиент хранит историю чатов локально, умеет работать с файлами и изображениями, поддерживает разные режимы AI-запросов и синхронизирует данные через отдельный backend.

Проект состоит из двух основных частей:

- `app/` - Android-клиент на Kotlin. В проекте используются Jetpack Compose для auth flow, XML/ViewBinding для основного интерфейса, Room, Retrofit, Coroutines и Android security APIs.
- `backend/` - Node.js/Express backend для авторизации, Telegram/VK login, JWT-сессий, синхронизации чатов, файлов, публичных ссылок и server-side AI proxy.

Backend держит API-ключи на сервере, а Android-клиент обращается к нему через `APP_API_BASE_URL`. Это позволяет не встраивать AI-секреты в APK и централизованно управлять лимитами, моделями и fallback-провайдерами.

<a id="features"></a>

## Возможности

| Сценарий | Что реализовано |
| --- | --- |
| AI-чат | Диалоги с локальной историей, потоковая генерация UI-ответов, повторная генерация, автозаголовки и краткие summary |
| Режимы запросов | Обычный чат, web search, shopping research, study mode, image generation и 18+ mode |
| AI-провайдеры | Server-side routing между OpenAI-совместимым API и VseGPT, публичный список моделей и настройка fallback |
| Файлы и медиа | Вложения изображений и документов, avatar upload, генерация изображений, анализ файлов и фото |
| Авторизация | Email legacy flow, Telegram bot/widget/native login, VK native login, миграция legacy email account в Telegram |
| Хранение | Room на Android, PostgreSQL на backend, синхронизация чатов и сообщений |
| Шаринг | Публичные ссылки на снапшоты чатов, список своих ссылок и отзыв ссылок |
| Безопасность | JWT, bcrypt, server-side secrets, biometric gate, локальные security-настройки Android |
| Ассистент | Digital assistant overlay, screen capture flow, перевод экрана и вопросы по текущему экрану |
| Монетизация | Дневные лимиты AI-запросов и rewarded ads для дополнительных запросов |
| Локализация | Ресурсы для английского, русского, украинского, французского, итальянского, грузинского и белорусского языков |

## Почему проект выделяется

- **Local-first Android-клиент.** История живет в Room и продолжает быть доступной на устройстве, а серверная синхронизация добавляется поверх локального состояния.
- **Server-side AI proxy.** Ключи, модели, лимиты и fallback-логика остаются на backend, а не в мобильном приложении.
- **Несколько auth-сценариев.** Поддержаны Telegram, VK и legacy email flow, включая миграцию существующих аккаунтов.
- **Практичный AI-интерфейс.** В приложении есть отдельные режимы для поиска, покупок, обучения, генерации изображений и работы с файлами.
- **Android assistant integration.** FreeChat можно использовать как экранного ассистента поверх других приложений.

<a id="architecture"></a>

## Архитектура

```mermaid
flowchart TD
    A["Android app"] --> B["Room local database"]
    A --> C["Express API"]
    C --> D["PostgreSQL"]
    C --> E["OpenAI-compatible API"]
    C --> F["VseGPT API"]
    C --> G["Telegram Bot / Login"]
    C --> H["VK ID"]
    C --> I["Local file storage"]
```

### Поток данных

1. Пользователь входит через Telegram, VK или legacy email flow.
2. Android-клиент получает JWT, хранит сессию и работает с локальной базой Room.
3. Сообщения и чаты синхронизируются с backend через `/api/sync`.
4. AI-запросы идут на `/api/ai/*`, backend выбирает провайдера и модель, применяет лимиты и проксирует запрос к upstream API.
5. Файлы загружаются через `/api/files/*`, публичные ссылки на чаты создаются через `/api/chat-shares`.

## Технологический стек

### Android

| Категория | Технологии |
| --- | --- |
| Язык | Kotlin |
| UI | Jetpack Compose, XML layouts, ViewBinding, Material Components |
| Архитектура | ViewModel, Repository pattern |
| Networking | Retrofit, OkHttp, Gson |
| Хранение | Room |
| Async | Kotlin Coroutines |
| Security | AndroidX Security Crypto, Biometric |
| Media | CameraX, ML Kit Text Recognition, Coil |
| Дополнительно | Markwon, Yandex Mobile Ads, VK ID SDK |

### Backend

| Категория | Технологии |
| --- | --- |
| Runtime | Node.js |
| API | Express |
| База данных | PostgreSQL |
| Auth | JWT, bcrypt |
| Validation | Zod |
| Uploads | Multer, sharp, file-type |
| Email | Nodemailer |
| Tests | node:test, Supertest |

## Структура проекта

```text
chatapp/
|-- app/
|   |-- src/main/java/com/example/chatapp/
|   |   |-- assistant/
|   |   |-- browser/
|   |   |-- camera/
|   |   |-- data/
|   |   |-- navigation/
|   |   |-- network/
|   |   |-- telegram/
|   |   |-- ui/
|   |   |-- util/
|   |   `-- viewmodel/
|   `-- src/main/res/
|-- backend/
|   |-- src/
|   |   |-- config/
|   |   |-- controllers/
|   |   |-- middleware/
|   |   |-- models/
|   |   |-- routes/
|   |   |-- schemas/
|   |   |-- services/
|   |   |-- utils/
|   |   `-- __tests__/
|   `-- sql/schema.sql
|-- scripts/
|-- uploads/
|-- .env.example
|-- build.gradle.kts
`-- settings.gradle.kts
```

<a id="quick-start"></a>

## Быстрый старт

### 1. Требования

- Android Studio с Android SDK 34
- JDK 17
- Node.js 18+
- PostgreSQL
- API-ключ OpenAI или VseGPT для AI-функций
- Telegram Bot Token и Bot Username, если нужен Telegram auth
- VK ID Client ID, если нужен VK login

### 2. Настроить переменные окружения

Скопируйте пример из корня проекта:

```bash
cp .env.example .env
```

Backend сначала читает `backend/.env`, затем корневой `.env`. Для локального запуска достаточно корневого файла.

Минимальный набор для backend:

```env
DATABASE_URL=postgres://postgres:postgres@localhost:5432/chatapp
JWT_SECRET=change-this-jwt-secret
VERIFICATION_CODE_SECRET=change-this-verification-secret
JSON_BODY_LIMIT=50mb
```

Минимальный набор для Android-клиента:

```env
APP_API_BASE_URL=http://10.0.2.2:4000/api/
CHAT_SHARE_PUBLIC_BASE_URL=http://10.0.2.2:4000
```

Для AI-функций заполните один или несколько провайдеров:

```env
OPENAI_API_KEY=
VSEGPT_API_KEY=
AI_CHAT_URL=https://api.vsegpt.ru/v1/chat/completions
AI_IMAGE_URL=https://api.vsegpt.ru/v1/images/generations
DAILY_AI_REQUEST_LIMIT=20
```

Для Telegram/VK auth:

```env
TELEGRAM_BOT_TOKEN=
TELEGRAM_BOT_USERNAME=
TELEGRAM_WIDGET_PUBLIC_BASE_URL=https://auth.example.com
TELEGRAM_LOGIN_CLIENT_ID=
TELEGRAM_LOGIN_REDIRECT_URI=
VKID_CLIENT_ID=
VKID_SCOPES=email
```

Если приложение запускается на Android Emulator, используйте `10.0.2.2` для доступа к backend на host-машине. Для физического устройства укажите локальный IP компьютера, например `http://192.168.1.20:4000/api/`.

### 3. Подготовить базу данных

Создайте PostgreSQL базу и примените схему:

```bash
psql "$DATABASE_URL" -f backend/sql/schema.sql
```

На Windows можно выполнить аналогичную команду из PowerShell, предварительно задав `DATABASE_URL` или подставив строку подключения напрямую.

### 4. Запустить backend

```bash
cd backend
npm install
npm start
```

Для разработки с авто-перезапуском:

```bash
npm run dev
```

По умолчанию backend слушает `http://localhost:4000`. Проверка состояния:

```bash
curl http://localhost:4000/health
```

### 5. Собрать Android-приложение

Откройте проект в Android Studio и запустите конфигурацию `app`, либо соберите debug APK из корня проекта:

```bash
./gradlew assembleDebug
```

На Windows:

```powershell
.\gradlew.bat assembleDebug
```

## Конфигурация AI

Backend публикует доступные модели через `/api/ai/models` и проверяет лимиты через `/api/ai/limits`. Сейчас в коде предусмотрены два типа провайдеров:

- `openai` - OpenAI-compatible endpoints для текста, vision, file search, image generation и image edit.
- `vsegpt` - VseGPT-compatible endpoints для текста, web search, vision-compatible сценариев и генерации изображений.

Режимы запроса из Android-клиента:

- `create_image` - генерация изображений;
- `search` - web search;
- `shopping` - исследование покупок;
- `study` - обучение и объяснения;
- обычный чат без специального режима.

<a id="api"></a>

## API

### System

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `GET` | `/health` | Проверка состояния backend |

### Auth

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/check-email` | Проверка legacy email account |
| `POST` | `/api/register` | Регистрация по email |
| `POST` | `/api/login` | Вход по email |
| `POST` | `/api/verify-email` | Подтверждение email-кода |
| `POST` | `/api/resend-code` | Повторная отправка кода |
| `GET` | `/api/me` | Текущий пользователь |
| `POST` | `/api/change-password` | Смена пароля авторизованного пользователя |

### Telegram auth

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `GET` | `/api/telegram-auth/widget` | Страница Telegram Login Widget |
| `GET` | `/api/telegram-auth/widget-callback` | Callback для Telegram widget |
| `POST` | `/api/telegram-auth/widget-login` | Завершение widget login |
| `POST` | `/api/telegram-auth/native-login` | Завершение native Telegram login |
| `POST` | `/api/telegram-auth/begin-registration` | Начать регистрацию через Telegram bot |
| `POST` | `/api/telegram-auth/begin-login` | Начать вход через Telegram bot |
| `POST` | `/api/telegram-auth/verify-code` | Проверить код из Telegram |
| `POST` | `/api/telegram-auth/complete-registration` | Завершить Telegram-регистрацию |
| `POST` | `/api/telegram-auth/complete-login` | Завершить Telegram-вход |
| `POST` | `/api/telegram-auth/begin-migration` | Начать миграцию legacy email account |
| `POST` | `/api/telegram-auth/complete-migration` | Завершить миграцию в Telegram |

### VK auth

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/vk-auth/native-login` | Завершение VK native login |

### AI

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `GET` | `/api/ai/models` | Список публично доступных AI-моделей |
| `GET` | `/api/ai/limits` | Текущие дневные лимиты пользователя |
| `GET` | `/api/ai/trending` | Популярные подсказки/темы |
| `POST` | `/api/ai/reward-ad` | Выдать бонус за rewarded ad |
| `POST` | `/api/ai/chat` | Основной AI chat/image/search запрос |
| `POST` | `/api/ai/title` | Генерация названия чата |
| `POST` | `/api/ai/summary` | Генерация summary диалога |

### Sync, files and sharing

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/sync` | Синхронизация чатов и сообщений |
| `GET` | `/api/chat-shares` | Список своих публичных ссылок |
| `GET` | `/api/chat-shares/:token` | Получить snapshot по token |
| `POST` | `/api/chat-shares` | Создать публичную ссылку |
| `POST` | `/api/chat-shares/chats/:chatId/revoke` | Отозвать ссылки конкретного чата |
| `DELETE` | `/api/chat-shares/:token` | Отозвать конкретную ссылку |
| `GET` | `/share/:token` | Публичная landing-страница shared chat |
| `POST` | `/api/files/avatar` | Загрузить avatar |
| `POST` | `/api/files/upload/image` | Загрузить изображение |
| `POST` | `/api/files/upload/document` | Загрузить документ |
| `GET` | `/api/files/:id/download` | Скачать документ |
| `DELETE` | `/api/files/:id` | Удалить файл |

<a id="tests"></a>

## Тесты

### Backend

```bash
cd backend
npm test
```

Покрыты auth routes, Telegram/VK auth, sync model, AI routes, file routes, chat shares, env parsing и middleware.

### Android

```bash
./gradlew test
./gradlew connectedAndroidTest
```

На Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat connectedAndroidTest
```

В проекте есть unit-тесты для auth logic, AI provider config, API service, sync conflict resolver, deep links, security localization и Compose auth flow.

## Важные замечания

- Не коммитьте `.env`, API-ключи, JWT secrets и реальные database credentials.
- `JWT_SECRET` и `VERIFICATION_CODE_SECRET` обязательны для production-like запуска backend.
- Android release build запрещает cleartext HTTP через `ALLOW_HTTP_BASE_URL=false`; для production используйте HTTPS.
- `CHAT_SHARE_PUBLIC_BASE_URL` должен быть абсолютным HTTP/HTTPS URL, так как используется для app links и публичных ссылок.
- Для Telegram Login Widget нужен публичный backend URL, доступный Telegram.
- Для физического Android-устройства `localhost` указывает на само устройство, поэтому в `APP_API_BASE_URL` нужен IP компьютера с backend.

## Направления развития

- Настроить CI для backend и Android tests.
- Добавить release pipeline и версионирование APK/AAB.
- Добавить скриншоты интерфейса в README.
- Вынести production uploads в S3-compatible storage.
- Расширить стратегию conflict resolution при синхронизации.
- Добавить OpenAPI-спецификацию для backend.

---

<p align="center">
  <strong>FreeChat</strong><br />
  Mobile AI chat with local persistence, server-side AI routing and Android assistant features.
</p>
