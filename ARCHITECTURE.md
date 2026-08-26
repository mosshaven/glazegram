# Architecture

## Scope

Текущее состояние включает authorization, chat list и функциональную основу chat screen. TDLib остаётся единственным persisted Telegram storage; приложение хранит только process-local projections и interaction state.

## Runtime Flow

```text
Android process
    |
    v
GlazegramApplication.onCreate()
    |
    v
TdLibRuntime.initialize(context)
    |
    v
Client.create(update handler)
    |
    v
Authorization/chat/message updates
    |
    v
TdLibRuntime projections -> ChatViewModel -> MessageListItem -> Compose
```

## Components

### `GlazegramApplication`

Application entry point. Вызывается один раз при старте Android process и запускает `TdLibRuntime.initialize()`.

### `TdLibRuntime`

Единая граница приложения с TDLib.

Сейчас выполняет:

- загрузку `libtdjni`;
- process-wide настройку TDLib logging;
- создание единственного TDLib client;
- передачу `SetTdlibParameters` с локальными database/files directories;
- преобразование authorization updates в `AuthUiState`;
- отправку phone, code, 2FA password и logout requests;
- публикацию состояния через `StateFlow`.
- загрузку main chat list через `LoadChats` и update stream;
- применение chat title, last message, unread count, pin/order updates;
- загрузку маленьких chat avatars через `DownloadFile`;
- local-first и remote message history через `GetChatHistory`;
- canonical message merge, send-state replacement и realtime updates;
- `GetMessage`/around-history для reply navigation;
- progressive TDLib file updates для media previews;
- normalized process-local user/basic-group/supergroup metadata projections;
- sender names/avatars, chat actions, member/online counts и chat permissions;
- viewport-based `ViewMessages` acknowledgements и mention counters;
- `GetMessageProperties`-gated delete actions;
- formatted text entities и forward-origin projections;
- TDLib `OptimizeStorage` cache cleanup.

### `ChatViewModel`

Per-chat state holder. Объединяет runtime flows с interaction state: reply composer, action target, resolved reply previews, navigation events и transient highlight. Raw TDLib requests остаются в `TdLibRuntime`.

Также координирует property-based message actions, own-send bottom navigation и visible-message read acknowledgements. Viewport geometry и temporary unseen counter остаются presentation state Compose screen.

### `MessagePresentation`

Чистое преобразование canonical message list в stable presentation items. Сообщения с одинаковым ненулевым `mediaAlbumId` группируются только для отображения; transport/storage identity каждого сообщения сохраняется.

TDLib database directory находится внутри `Context.filesDir/tdlib`. Поэтому повторный запуск приложения позволяет TDLib восстановить сессию без отдельного хранилища Telegram-данных.

## UI Architecture

Material 3 — активная дизайн-система. Структура инкрементальная:

### Theme tokens (`ui/theme/`)

- `GlazegramTheme` — входная тема: dynamic Monet color scheme на Android 12+, fallback schemes, luminance-based system bar icons, wiring typography и shapes.
- `Type.kt` — типографическая шкала Material roles с единой точкой смены font family (бинарные шрифты не добавлены; архитектура позволяет выбрать шрифт позже).
- `Shape.kt` — шкала форм (extraSmall → extraLarge).
- `Spacing.kt` — токены отступов (xs → xxl).
- `Motion.kt` — общие duration/easing токены для согласованной анимации.

### Reusable components (`ui/components/`)

- `rememberDecodedImage` — граница асинхронной загрузки изображений: decode на `Dispatchers.IO`, результат в Compose state, bounded LRU memory cache, ключи различают file path и byte-данные (minithumbnail). Используется аватарами, media previews и media viewer.
- `GlazegramAvatar` — аватар: async image + initials fallback.
- `EmptyState` — примитив пустых состояний.
- `Modifier.pressScale` — тактильный press-feedback (scale-on-press) на motion-токенах.

### Feature composables

Feature-экраны (chat list, chat, settings, auth) пока расположены в `MainActivity.kt`. Вынос в feature-пакеты запланирован отдельным рефакторингом; новые экраны должны размещаться в feature-пакетах сразу.

### `MainActivity`

Единственная Activity текущей версии. Устанавливает Compose content и отображает authorization screen.

Authorization и временные Compose screens пока находятся рядом с Activity. Chat state и interaction orchestration уже вынесены в `ChatViewModel`; следующим UI-рефакторингом chat composables нужно перенести в отдельный feature package.

## UI Theme

`GlazegramTheme` использует Material 3. На Android 12+ цвета берутся из системной dynamic color scheme (Monet); на старых версиях используются Material fallback color schemes. Текущая тема поддерживает light/dark mode через системную настройку. Детали в разделе UI Architecture.

## TDLib

TDLib подключается как pinned Android AAR:

- generated Java API в package `org.drinkless.tdlib`;
- native `libtdjni.so`;
- поддерживаемые Android ABI из поставляемого AAR.

Приложение не дублирует Telegram storage. Сообщения, чаты, медиа и синхронизация остаются ответственностью TDLib.

## Configuration

`TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` используются только на этапе Gradle configuration и читаются в таком порядке:

1. environment variable;
2. Gradle property;
3. `~/.glazegram/secrets.env`.

При отсутствии значений bootstrap build остаётся возможным. Phase 1 должен добавить явную validation перед началом авторизации.

Generated `BuildConfig` и APK могут содержать локально переданные Telegram application credentials. Поэтому APK и build outputs не должны коммититься или публиковаться без отдельной политики релизной сборки.

## Project Structure

```text
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/glazegram/
│           ├── GlazegramApplication.kt
│           ├── MainActivity.kt
│           ├── chat/
│           │   ├── ChatViewModel.kt
│           │   └── MessagePresentation.kt
│           ├── tdlib/
│           │   ├── AuthUiState.kt
│           │   └── TdLibRuntime.kt
│           ├── ui/components/
│           │   ├── AsyncImage.kt
│           │   ├── GlazegramAvatar.kt
│           │   ├── PressScale.kt
│           │   └── States.kt
│           └── ui/theme/
│               ├── GlazegramTheme.kt
│               ├── Motion.kt
│               ├── Shape.kt
│               ├── Spacing.kt
│               └── Type.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── ROADMAP.md
└── ARCHITECTURE.md
```

## Planned Direction

Следующие архитектурные шаги:

- serialized reducer вместо mutation из нескольких TDLib callbacks;
- отдельные chat/message/file repositories без дублирования TDLib database;
- вынос chat composables из `MainActivity` в feature-пакеты;
- album boundary completion и production mosaic calculator;
- persistent appearance preference вместо зависимости только от system theme;
- reliable push notifications вместо постоянного foreground connection как единственного background mechanism.
