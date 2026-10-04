# 07 — Гайд для продолжения разработки

Этот документ — точка входа для любого разработчика/клиента, который продолжит
работу над проектом. Прочитайте его целиком перед изменениями.

## Быстрый вход

1. Прочитайте [README.md](../README.md) и [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
2. Соберите проект локально: [02-BUILDING.md](02-BUILDING.md) (`./gradlew assembleDebug`).
3. Запустите на устройстве, добавьте тестовый M3U-плейлист (например, публичный
   список тестовых HLS-стримов) и пройдитесь по всем экранам.
4. Изучите `AppContainer` (di/AppContainer.kt) — это карта всех сервисов.

## Карта кода (что где искать)

| Задача | Файлы |
|---|---|
| Добавить поле настройки | `SettingsRepository` (ключ + AppSettings + сеттер) → `SettingsScreen` |
| Новый экран | `ui/screens/<name>/`, маршрут в `Routes` + `AppRoot` |
| Изменить парсинг M3U | `data/remote/M3uParser.kt` + тест `M3uParserTest` |
| Изменить парсинг EPG | `data/remote/XmlTvParser.kt` + `XmlTvParserTest` |
| Xtream API | `data/remote/XtreamClient.kt` (все endpoint'ы и URL-шаблоны) |
| Схема БД | `data/db/` — при изменении сущностей поднимите `version` в `AppDatabase` и добавьте миграцию (сейчас стоит `fallbackToDestructiveMigration` — данные пользователей потеряются!) |
| Плеер / управление | `player/PlaybackService.kt`, `player/PlayerViewModel.kt` |
| Catch-up | `player/Catchup.kt` (+ `CatchupTest`) |
| Самообновление | `data/repo/UpdateRepository.kt`, `scripts/make_update_manifest.py` |
| CI/подпись | `.github/workflows/android.yml`, `keystore/`, `app/build.gradle.kts` |
| Версии и релизы | `version.properties`, `scripts/version.sh`, `scripts/record_release.py`, [04-RELEASE.md](04-RELEASE.md) |

## Договорённости

- **Язык кода** — Kotlin; комментарии и javadoc — английские; документация —
  русская. UI-строки — только через `res/values/strings.xml` (EN) +
  `res/values-ru/strings.xml` (RU), обе рассинхронизировать нельзя
  (lint `MissingTranslation` сейчас отключён — следите вручную).
- **DI** — вручную через `AppContainer`/`ServiceLocator`. Новый сервис:
  добавьте `val x by lazy { X(…) }` в `AppContainer`.
- **ViewModel** — `AndroidViewModel` (+`SavedStateHandle` для nav-аргументов),
  фабрики не нужны. Бизнес-логика — в репозиториях, VM только состояние.
- **Data flow**: Room → `Flow` → `stateIn(viewModelScope)` → `collectAsState()`.
- **uid-подход**: любые новые пользовательские флаги каналов должны жить в
  `ChannelEntity` и переживать refresh (см. 01-ARCHITECTURE.md).
- **Тесты**: парсеры и чистая логика покрываются unit-тестами (`app/src/test`).
  Новые форматы/кейсы парсеров добавляйте вместе с тестами.
- **Коммиты**: `feat:/fix:/docs:/ci:/chore:` (conventional-ish), одно логическое
  изменение — один коммит.
- **versionCode руками не трогаем** — его назначает CI (run_number).
- **versionName руками не трогаем** — схема `мажор.минор.<номер релиза>`,
  patch считает CI по тегам (`scripts/version.sh`). Менять можно только
  `VERSION_MAJOR`/`VERSION_MINOR` в `version.properties`.

## Как выпускать версии

Мерж в `main` → CI сам присваивает следующий номер релиза, ставит тег `vX.Y.N`
и публикует Release с подписанным APK + `app-update.json` → приложения
пользователей сами увидят обновление. Нужна конкретная версия — поставьте тег
`vX.Y.Z` вручную. Полный процесс: [04-RELEASE.md](04-RELEASE.md). Всегда
обновляйте `CHANGELOG.md` (таблицу «История релизов» ведёт CI).

## Критические точки (легко сломать)

1. **`versionCode` только растёт** — не переименовывайте workflow-файл
   (см. [03-CI-CD.md](03-CI-CD.md#номера-сборок-build-numbers)).
2. **Ключ подписи** — см. [06-SIGNING-KEYS.md](06-SIGNING-KEYS.md). Потеря =
   принудительная переустановка у всех пользователей.
3. **`app-update.json` схема** — `versionCode` обязателен и это критерий
   обновления; не меняйте имена полей без обратной совместимости (старые
   приложения должны уметь читать новый манифест). Поля можно только добавлять
   (так добавился `releaseNumber`).
4. **applicationId** (`com.iptvplayer.app`) менять нельзя без миграции
   самообновления — старые приложения не увидят новые версии.
5. **Room-версия** — любое изменение сущностей = миграция или потеря данных.

## Roadmap (что докрутить)

Идеи по развитию, отсортированы по пользе/усилиям:

- [ ] R8/minify включить (`isMinifyEnabled = true`) + прогнать все сценарии
      (правила уже частично в `app/proguard-rules.pro`).
- [ ] Глобальные избранное (кросс-плейлист) и режим «Недавно смотрел».
- [ ] Экспорт избранного в бэкап.
- [ ] Сталкер-порталы (Stalker Portal / IPTV через MAC) — как в Televizo.
- [ ] Drag-and-drop сортировка каналов, мультивыбор.
- [ ] Полноценный Cast: mini-controller, очередь на приёмнике.
- [ ] EPG: напоминания о передачах (AlarmManager/WorkManager).
- [ ] MVP-режим каналов (num-pad), краткое «info»-оверлей при зеппинге.
- [ ] Несколько профилей EPG-сопоставления (ручная привязка канала к tvg-id).
- [ ] Тёмная тема TV-баннера, больше плотностей иконок.
- [ ] Instrumented-тесты (Espresso/Compose UI) на ключевые сценарии.
- [ ] Переводы: en/ru есть; добавить uk, de, es через Weblate/PR.

## Известные ограничения (v1)

- Catch-up для Xtream использует время в UTC (панель может ждать локальный
  часовой пояс сервера) — при сдвиге смотрите `Catchup.xtreamFallback`.
- Заголовки `#EXTVLCOPT` применяются к потоку только при запуске нового канала
  (не для уже играющего).
- Guide: колонка имён каналов скроллится вместе с таймлайном (в планах —
  закрепление).
- UDP/RTP — лучший effort; для экзотических протоколов используйте внешний
  плеер (кнопка в списке каналов и в плеере).
