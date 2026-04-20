<h1 align="center">ChatApp</h1>

<p align="center">
  <img src="app/src/main/res/drawable/logo.png" alt="ChatApp logo" width="140" />
</p>

<p align="center">
  <strong>Android AI chat application</strong><br />
  Telegram-first authentication, local-first chat history, image generation, smart mode routing and sync via a dedicated Node.js backend.
</p>

<p align="center">
  <a href="#overview">Обзор</a> |
  <a href="#features">Возможности</a> |
  <a href="#architecture">Архитектура</a> |
  <a href="#quick-start">Быстрый старт</a> |
  <a href="#api">API</a> |
  <a href="#tests">Тесты</a>
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-34-1f7a4d?style=for-the-badge&logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-2f4f9d?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack_Compose-UI-0d9488?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img alt="Node.js" src="https://img.shields.io/badge/Node.js-Backend-22543d?style=for-the-badge&logo=nodedotjs&logoColor=white" />
  <img alt="Express" src="https://img.shields.io/badge/Express-API-222222?style=for-the-badge&logo=express&logoColor=white" />
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-Data-1d4ed8?style=for-the-badge&logo=postgresql&logoColor=white" />
</p>

---

<a id="overview"></a>

## Обзор

**ChatApp** - это мобильный AI-чат для Android, который сочетает удобный чат-интерфейс, генерацию изображений, Telegram-авторизацию, локальное хранение истории и серверную синхронизацию.

Проект состоит из двух частей:

- `app/` - Android-клиент на Kotlin с Jetpack Compose, ViewBinding, Room и Retrofit.
- `backend/` - Node.js/Express backend для авторизации, Telegram flow, JWT и sync API на PostgreSQL.

README написан так, чтобы по нему можно было быстро понять продукт, архитектуру и поднять проект локально.

<a id="features"></a>

## Возможности

| Сценарий | Что уже реализовано |
| --- | --- |
| AI-чат | Потоковая генерация ответов, локальная история сообщений, повторная генерация ответа, автогенерация названий чатов |
| Режимы работы | Обычный чат, web search, shopping research, study mode, image generation |
| AI-провайдеры | Gemini и VseGPT с автоматическим fallback между моделями |
| Медиа | Отправка изображений и файлов, camera/photo/file picker, image generation по prompt |
| Авторизация | Telegram registration/login, миграция legacy email account в Telegram, JWT-сессии |
| Хранение данных | Room для локальной истории, PostgreSQL для серверной синхронизации |
| Пользовательский опыт | Анонимный чат без сохранения истории, голосовой ввод, персонализация, смена языка, настройки безопасности |
| Монетизация | Дневной лимит запросов и rewarded ads для получения дополнительных запросов |
| Тесты | Unit/integration tests для auth backend и Android auth flow |

## Почему проект выделяется

- **Telegram-first onboarding**. Вход и регистрация завязаны на Telegram-боте, а не только на email.
- **Local-first подход**. Чаты живут локально в Room и затем синхронизируются с backend.
- **Гибридный Android UI**. Авторизация написана на Compose, основной чат использует ViewBinding и XML layouts.
- **Умная маршрутизация AI**. Приложение само выбирает подходящий backend и модель под текущий сценарий.
- **Расширяемая серверная часть**. Отдельные роуты для auth, Telegram auth и sync делают backend понятным и масштабируемым.

<a id="architecture"></a>

## Архитектура

```mermaid
flowchart TD
    A["Android App"] --> B["Room Local Database"]
    A --> C["Auth / Sync / AI API (Express)"]
    C --> D["Gemini API"]
    C --> E["VseGPT API"]
    C --> F["PostgreSQL"]
    C --> G["Telegram Bot API"]
    C --> H["SMTP Provider (optional)"]
```

### Поток данных

1. Пользователь проходит регистрацию или вход через Telegram или legacy email flow.
2. Android-клиент сохраняет сессию и локальные данные.
3. Чаты и сообщения хранятся локально в Room.
4. Backend принимает auth и sync запросы, выдает JWT и синхронизирует данные с PostgreSQL.
5. Android sends AI requests to the backend; backend proxies them to Gemini or VseGPT and keeps API keys server-side.

## Технологический стек

### Mobile

| Категория | Технологии |
| --- | --- |
| Язык | Kotlin |
| UI | Jetpack Compose, XML, ViewBinding, Material Components |
| Архитектура | ViewModel, Repository pattern |
| Networking | Retrofit, OkHttp, Gson |
| Хранение | Room |
| Async | Kotlin Coroutines |
| Дополнительно | ML Kit, Coil, Markwon, Yandex Mobile Ads |

### Backend

| Категория | Технологии |
| --- | --- |
| Runtime | Node.js |
| API | Express |
| База данных | PostgreSQL |
| Auth | JWT, bcrypt |
| Validation | Zod |
| Email | Nodemailer |
| Tests | node:test, Supertest |

## Структура проекта

```text
chatapp/
|-- app/
|   |-- src/main/java/com/example/chatapp/
|   |   |-- data/
|   |   |-- navigation/
|   |   |-- network/
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
|   |   |-- utils/
|   |   `-- __tests__/
|   `-- sql/schema.sql
|-- build.gradle.kts
|-- settings.gradle.kts
`-- .env
```

<a id="quick-start"></a>

## Быстрый старт

### 1. Требования

- Android Studio с Android SDK 34
- JDK 17
- Node.js 18+
- PostgreSQL
- API ключи для Gemini и VseGPT
- Telegram bot token и username, если нужен Telegram auth flow

### 2. Поднять backend

Скопируйте пример переменных окружения:

```bash
cp backend/.env.example backend/.env
```

Заполните `backend/.env`:

```env
PORT=4000
JSON_BODY_LIMIT=10mb
DATABASE_URL=postgres://postgres:postgres@localhost:5432/chatapp
SECRETS_DIR=backend/secrets
JWT_SECRET_FILE=backend/secrets/jwt_secret
JWT_EXPIRES_IN=7d
MAIL_FROM=no-reply@freechat.local
SMTP_HOST=
SMTP_PORT=587
SMTP_SECURE=false
SMTP_USER=
SMTP_PASS_FILE=backend/secrets/smtp_pass
TELEGRAM_BOT_TOKEN_FILE=backend/secrets/telegram_bot_token
TELEGRAM_BOT_USERNAME=
PRIMARY_AI_API_KEY_FILE=backend/secrets/primary_ai_api_key
SECONDARY_AI_API_KEY_FILE=backend/secrets/secondary_ai_api_key
PRIMARY_AI_STREAM_URL_TEMPLATE=
SECONDARY_AI_CHAT_URL=
PRIMARY_AI_TEXT_MODEL=
SECONDARY_AI_TEXT_MODEL=
SECONDARY_AI_TITLE_MODEL=
SECONDARY_AI_SUMMARY_MODEL=
SECONDARY_AI_AUDIT_MODEL=
```

Примените SQL схему из `backend/sql/schema.sql` к вашей базе PostgreSQL.

После этого запустите backend:

```bash
cd backend
npm install
npm start
```

Backend поднимется на `http://localhost:4000`, health endpoint: `GET /health`.

### 3. Настроить Android-клиент

В корне проекта создайте или обновите файл `.env`:

```env
APP_API_BASE_URL=http://10.0.2.2:4000/api/
```

> Если запускаете приложение на Android Emulator, используйте `10.0.2.2`.
> Если запускаете на физическом устройстве, укажите локальный IP вашей машины, например `http://192.168.1.20:4000/api/`.

После этого откройте проект в Android Studio и запустите `app`.

Альтернативно можно собрать debug-сборку из корня проекта:

```bash
./gradlew assembleDebug
```

## Конфигурация AI

В приложении есть несколько режимов выбора AI backend:

- `auto` - сначала пытается использовать основной провайдер, затем делает fallback при необходимости.
- `free` - ориентирован на Gemini.
- `vsegpt` - ориентирован на VseGPT.

Режимы запроса в интерфейсе:

- `create_image`
- `search`
- `shopping`
- `study`
- стандартный чат

В зависимости от режима приложение выбирает подходящую модель, включая image generation и vision-compatible модели.

<a id="api"></a>

## API

### Auth endpoints

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/check-email` | Проверка существования legacy email аккаунта |
| `POST` | `/api/register` | Регистрация по email |
| `POST` | `/api/login` | Вход по email |
| `POST` | `/api/verify-email` | Подтверждение email-кода |
| `POST` | `/api/resend-code` | Повторная отправка кода |
| `POST` | `/api/change-password` | Смена пароля авторизованного пользователя |

### Telegram auth endpoints

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/telegram-auth/begin-registration` | Начать Telegram-регистрацию |
| `POST` | `/api/telegram-auth/begin-login` | Начать Telegram-вход |
| `POST` | `/api/telegram-auth/verify-code` | Проверить код из Telegram |
| `POST` | `/api/telegram-auth/complete-registration` | Завершить Telegram-регистрацию |
| `POST` | `/api/telegram-auth/complete-login` | Завершить Telegram-вход |
| `POST` | `/api/telegram-auth/begin-migration` | Начать миграцию legacy email аккаунта |
| `POST` | `/api/telegram-auth/complete-migration` | Завершить миграцию аккаунта в Telegram |

### Sync endpoint

| Method | Endpoint | Назначение |
| --- | --- | --- |
| `POST` | `/api/sync` | Синхронизация чатов и сообщений между Room и PostgreSQL |

<a id="tests"></a>

## Тесты

### Backend

```bash
cd backend
npm test
```

Покрыты ключевые сценарии:

- регистрация по email
- login и verify flow
- change password
- Telegram registration/login
- migration flow
- поведение Telegram bot service

### Android

```bash
./gradlew test
./gradlew connectedAndroidTest
```

В проекте уже есть тесты для auth logic и Compose auth flow.

## Важные замечания

- AI keys are read by the backend from files in `backend/secrets/` and are not added to Android `BuildConfig`.
- Android calls the backend AI proxy instead of calling Gemini or VseGPT directly.
- Backend поддерживает SMTP как опциональную возможность. Если SMTP не настроен, email verification может использовать fallback-поведение.
- Для локальной отладки Android-клиенту нужно явно указать корректный `APP_API_BASE_URL`.

## Подходящие направления для развития

- вынести все AI-вызовы на backend для более безопасной работы с ключами
- добавить CI для Android и backend тестов
- сделать release pipeline и versioning
- добавить визуальные скриншоты интерфейса в README
- расширить стратегию sync и conflict resolution

---

<p align="center">
  <strong>ChatApp</strong><br />
  Mobile AI chat with Telegram auth, local persistence and a clean backend foundation.
</p>
