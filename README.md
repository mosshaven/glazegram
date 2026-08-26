# Glazegram

> Независимый Telegram-клиент для Android, создаваемый с нуля на базе TDLib.

Glazegram не является форком официального Telegram-клиента. Проект использует TDLib для сетевого взаимодействия, авторизации, синхронизации и локального хранения Telegram-данных.

## Статус

Версия `0.1.0-alpha.1` содержит функциональное ядро Android-клиента. Это ранняя alpha: основные потоки работают, но Telegram-функции и chat UI ещё неполны.

**Material 3 — активный UI-директ Glazegram.** Визуальное качество, дизайн-система и полировка развиваются вместе с функциональностью, а не откладываются на потом. Внешний вид строится как целостный Material-клиент с dynamic Monet-цветом; кастомизация и альтернативные системы оформления (Apple-вдохновлённый режим, Liquid Glass) планируются отдельно и не входят в текущую функциональность.

- Android-проект на Kotlin и Jetpack Compose
- Gradle Kotlin DSL и Gradle Wrapper
- TDLib Android AAR с pinned-версией
- TDLib authorization client и update flow
- Вход по номеру телефона, коду и 2FA password
- Logout и восстановление сессии через TDLib database
- Список чатов с realtime updates, unread counters и pinned ordering
- Chat titles, last message previews и TDLib avatar downloads
- Local-first история, pagination и realtime message updates
- Отправка текста, статусы pending/sent/read/failed и replies
- Reply navigation, long-press actions Reply/Copy
- Photo/video/document/audio preview models и media albums
- Sender names/avatars, typing action, member/online counts
- Permission-aware composer для read-only channels
- Foreground TDLib service и resume reconciliation
- Material 3 с dynamic Monet colors на Android 12+
- Debug APK собирается успешно

Не завершены notifications/push, search, profiles, contacts, reactions, edit/delete, production media mosaic, cross-chat reply navigation и полная Telegram content taxonomy. Возможны медленная первая server synchronization и ошибки media playback.

## Стек

| Компонент | Технология |
| --- | --- |
| Platform | Android |
| Language | Kotlin |
| UI | Jetpack Compose |
| Telegram API | TDLib |
| Async | Kotlin Coroutines |
| Build | Gradle Kotlin DSL |
| Min SDK | 23 |
| Compile SDK | 36 |

## Сборка

Требования:

- JDK 21
- Android SDK с API 36
- Android SDK Build-Tools 36

Сборка debug APK:

```bash
./gradlew :app:assembleDebug
```

Результат:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Проверка проекта:

```bash
./gradlew :app:check
```

Если Android SDK не настроен глобально, перед сборкой укажи `ANDROID_HOME` или создай стандартный `local.properties`. `local.properties` не отслеживается Git.

## Установка APK

При подключённом Android-устройстве с включённой USB-отладкой:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Либо APK можно открыть через файловый менеджер Android после передачи файла на устройство.

## Credentials

Реальные credentials не хранятся в репозитории.

Создай локальный файл:

```text
~/.glazegram/secrets.env
```

Минимальное содержимое:

```dotenv
TELEGRAM_APP_ID=your_telegram_app_id
TELEGRAM_APP_HASH=your_telegram_app_hash
```

Gradle читает значения в следующем порядке:

1. Environment variables
2. Gradle properties
3. `~/.glazegram/secrets.env`

Шаблон переменных находится в `secrets.env.example`. Файлы credentials, keystore и `google-services.json` исключены из Git.

## Документация

- `ARCHITECTURE.md` — фактически реализованная архитектура
- `ROADMAP.md` — этапы разработки
- `AGENTS.md` — правила проекта
- `CLAUDE.md` и `GEMINI.md` — compatibility-инструкции для coding agents

## Roadmap

Текущий этап — завершение chat interaction foundation и стабилизация realtime/media behavior. Подробный фактический статус находится в `ROADMAP.md`.

Тема использует Material 3 с dynamic Monet-палитрой устройства. Дизайн-система (типографика, формы, отступы, motion) и переиспользуемые компоненты развиваются инкрементально вместе с функциональным ядром. Альтернативные системы оформления и глубокая кастомизация появятся после стабилизации Material-реализации.

## Лицензия

См. `LICENSE`.
