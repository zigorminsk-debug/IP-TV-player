# IP-TV Player (Android)

**IPTV-плеер для Android — аналог Televizo**: работает с вашими M3U-плейлистами и
аккаунтами Xtream Codes, показывает программу передач (EPG), архивы (catch-up),
поддерживает избранное, родительский контроль, Chromecast и Android TV.

> ⚠️ **Приложение — только плеер.** Оно не содержит и не предоставляет никаких
> каналов, фильмов и плейлистов. Для просмотра нужен свой плейлист/подписка от
> вашего IPTV-провайдера.

[![Build & Release](https://github.com/zigorminsk-debug/IP-TV-player/actions/workflows/android.yml/badge.svg)](https://github.com/zigorminsk-debug/IP-TV-player/actions/workflows/android.yml)

## Возможности

| Функция | Описание |
|---|---|
| 📺 Плейлисты | Неограниченное количество M3U (URL) и Xtream Codes аккаунтов |
| 🎬 Xtream Codes | Каналы, фильмы (VOD), сериалы, EPG через `player_api.php` |
| 📖 EPG (XMLTV) | Неограниченное количество источников, телепрограмма на 7 дней, «сейчас/далее» |
| ⏪ Catch-up (архив) | `catchup`/`catchup-source` из M3U, `tv_archive` Xtream, просмотр «с начала передачи» |
| ⭐ Избранное, поиск, сортировка | По каналам и текущим передачам |
| 🔒 Родительский контроль | PIN (PBKDF2), блокировка и скрытие категорий/каналов |
| 🎛 Плеер | Media3/ExoPlayer: HLS/DASH/TS/RTMP, выбор видео/аудио/субтитров, аспект, скорость потока |
| 📱 PiP, фон, таймер сна | Картинка-в-картинке, фоновое воспроизведение с медиа-уведомлением, таймер сна |
| 📡 Chromecast | Через CastPlayer (на устройствах без GMS отключается автоматически) |
| 📺 Android TV | D-pad навигация, баннер, LEANBACK_LAUNCHER |
| 🔁 Автообновление плейлистов | Фоновое, по расписанию (WorkManager) |
| ⬆️ Автообновление приложения | Проверка GitHub Releases, скачивание и установка поверх (с сохранением данных) |
| 💾 Экспорт/импорт настроек | Плейлисты и EPG-источники в JSON |
| 🔄 Возобновление просмотра | Продолжение фильмов и серий с места остановки |

Полная таблица соответствия функциям Televizo — в [docs/08-FEATURES.md](docs/08-FEATURES.md).

## Установка

Скачайте свежий APK из [Releases](https://github.com/zigorminsk-debug/IP-TV-player/releases/latest)
и установите его. Все релизы подписаны **одним и тем же постоянным ключом**, поэтому
новые версии устанавливаются поверх старых без удаления (плейлисты и настройки
сохраняются). Подробности — [docs/06-SIGNING-KEYS.md](docs/06-SIGNING-KEYS.md).

Системные требования: Android 6.0+ (API 23), телефон, планшет, TV-бокс или Android TV.

Приложение также **само проверяет обновления**: при выходе новой версии покажет
диалог со скачиванием и установкой (требуется однократно разрешить установку из
этого источника). Работает через `app-update.json`, публикуемый CI — см.
[docs/05-AUTO-UPDATE.md](docs/05-AUTO-UPDATE.md).

## Быстрый старт

1. Откройте приложение → **Добавить плейлист**.
2. Выберите тип:
   * **M3U** — вставьте ссылку на плейлист (и, при желании, ссылку XMLTV EPG);
   * **Xtream Codes** — введите сервер, логин и пароль (EPG добавится автоматически).
3. Дождитесь первичной загрузки каналов и откройте плейлист.

## Сборка и разработка

```bash
git clone https://github.com/zigorminsk-debug/IP-TV-player.git
cd IP-TV-player
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew assembleRelease      # release APK (подписан ключом из keystore/)
./gradlew testDebugUnitTest    # юнит-тесты
```

Требуется JDK 17. Подробности, включая локальную нумерацию версий — в
[docs/02-BUILDING.md](docs/02-BUILDING.md).

**Сборка в облаке**: каждый push автоматически собирается GitHub Actions
(тесты → debug/release APK → AAB, см. [docs/03-CI-CD.md](docs/03-CI-CD.md)).
Релиз создаётся автоматически по тегу `vX.Y.Z` — см. [docs/04-RELEASE.md](docs/04-RELEASE.md).

## Документация

| Документ | Содержание |
|---|---|
| [docs/01-ARCHITECTURE.md](docs/01-ARCHITECTURE.md) | Архитектура, слои, ключевые классы, потоки данных |
| [docs/02-BUILDING.md](docs/02-BUILDING.md) | Локальная сборка, требования, параметры |
| [docs/03-CI-CD.md](docs/03-CI-CD.md) | Автосборка GitHub Actions, номера сборок, секреты |
| [docs/04-RELEASE.md](docs/04-RELEASE.md) | Как выпустить версию (тег → релиз), changelog |
| [docs/05-AUTO-UPDATE.md](docs/05-AUTO-UPDATE.md) | Самообновление приложения, формат `app-update.json` |
| [docs/06-SIGNING-KEYS.md](docs/06-SIGNING-KEYS.md) | Ключ подписи: где лежит, бэкап, ротация, риски |
| [docs/07-CONTINUATION.md](docs/07-CONTINUATION.md) | **Гайд для тех, кто продолжит разработку**: обзор кода, договорённости, roadmap |
| [docs/08-FEATURES.md](docs/08-FEATURES.md) | Таблица паритета с Televizo, что реализовано/в плане |
| [CHANGELOG.md](CHANGELOG.md) | История версий |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Как вносить изменения |

## Стек технологий

- **Kotlin 2.1**, Jetpack **Compose** (Material 3), Single-Activity
- **Media3 / ExoPlayer 1.5** + MediaSessionService (фон, уведомление)
- **Room** (плейлисты, каналы, EPG-кэш), **DataStore** (настройки)
- **OkHttp** (M3U/XMLTV/Xtream API/обновления), **kotlinx.serialization**
- **WorkManager** (фоновое обновление), **Coil** (логотипы)
- Manual DI (без Hilt/Koin — просто и читаемо)

## Лицензия

[MIT](LICENSE). Плеер не аффилирован с Televizo; это самостоятельное открытое
приложение с аналогичной функциональностью.

---

### English summary

IP-TV Player is an open-source Android IPTV player (a Televizo-style app): it
plays **your own** M3U playlists and Xtream Codes accounts, supports XMLTV EPG,
catch-up archives, favorites, parental control, Chromecast, PiP, Android TV and
in-app self-updates from GitHub Releases. CI builds every push; every build gets
an auto-incremented build number (`versionCode` = GitHub Actions run number);
releases are created automatically from `vX.Y.Z` tags and signed with a
persistent key so updates install over the previous version. See `docs/` (in
Russian) for architecture, CI/CD, release process, key management and a
continuation guide.
