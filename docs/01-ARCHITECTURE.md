# 01 — Архитектура

> Документ описывает устройство приложения, чтобы любой разработчик мог быстро
> войти в проект. Общее правило: читаемость важнее «умности».

## Обзор

Single-Activity Compose-приложение с ручным DI и одним Gradle-модулем `:app`.
Воспроизведение вынесено в foreground-сервис (Media3 MediaSessionService),
поэтому видео/аудио продолжает играть в фоне, а UI подключается к плееру через
MediaController.

```
┌────────────────────────── MainActivity (Compose) ──────────────────────────┐
│  AppRoot (NavHost + нижняя навигация + диалоги самообновления)             │
│   ├─ PlaylistsScreen   список/добавление/редактирование плейлистов         │
│   ├─ ChannelsScreen    каналы/фильмы/сериалы, категории, поиск, сортировка │
│   ├─ GuideScreen       телепрограмма (7 дней, сетка)                       │
│   ├─ SeriesScreen      сезоны и эпизоды сериала                            │
│   ├─ SearchScreen      глобальный поиск по каналам и передачам             │
│   ├─ PlayerScreen      плеер: оверлеи, зеппинг, треки, catch-up, PiP, TV   │
│   ├─ SettingsScreen    настройки + экспорт/импорт + обновления             │
│   ├─ EpgSourcesScreen  управление источниками XMLTV                        │
│   └─ EditorScreen      редактор родительского контроля (блок/скрытие)      │
└────────────────────────────────────────────────────────────────────────────┘
        │ ViewModel-ы (AndroidViewModel + SavedStateHandle для nav-аргументов)
        ▼
┌─────────────────────────── AppContainer (di/) ─────────────────────────────┐
│ http (OkHttp) · json · db (Room) · settings · playlists · epg              │
│ progress · updates · backup · parental · playbackQueue                     │
└────────────────────────────────────────────────────────────────────────────┘
        │                                    │
        ▼                                    ▼
  PlaybackService (Media3)            RefreshWorker (WorkManager)
  ExoPlayer + MediaSession            фоновое обновление плейлистов/EPG
```

## Слои и пакеты

| Пакет | Назначение |
|---|---|
| `data.model` | Доменные модели и енамы (`PlaylistType`, `ChannelKind`, `PlayRequest`, `RefreshResult`…) |
| `data.db` | Room: сущности (`PlaylistEntity`, `ChannelEntity`, `ProgrammeEntity`…), DAO, `AppDatabase` |
| `data.remote` | `Http` (OkHttp + скачивание), `M3uParser`, `XmlTvParser`, `XtreamClient`, `Base64Codec` |
| `data.repo` | `PlaylistRepository`, `EpgRepository`, `SettingsRepository`, `UpdateRepository`, `ProgressRepository`, `BackupManager` |
| `player` | `PlaybackService`, `PlaybackQueue`, `MediaItems`, `IptvDataSourceFactory`, `Catchup`, `TrackSelections`, `CastOptionsProvider`, `ExternalPlayer`, `PlayerViewModel` |
| `parental` | `ParentalManager` (PIN-сессии) |
| `work` | `RefreshWorker` + `RefreshScheduler` |
| `ui.*` | Compose-экраны, тема, общие компоненты, навигация (`AppRoot`, `Routes`) |
| `util` | `Format` (время/даты) |

## Ключевые решения

### Идентификаторы сущностей (uid)
`uid = "$playlistId:$kind:$remoteId"` — детерминирован, поэтому **переживает
обновление плейлиста**: флаги (избранное, скрыто, блок, прогресс) сохраняются
между синхронизациями, т.к. новые строки создаются с теми же uid.

### Обновление плейлиста
`PlaylistRepository.refresh(id)`:
1. M3U: скачать → `M3uParser` (строковый парсер, поддерживает `tvg-*`,
   `group-title`, `catchup*`, `#EXTVLCOPT`, `#EXTGRP`) → заменить категории и
   каналы в одной Room-транзакции с сохранением флагов.
2. Xtream: `player_api.php` — категории и потоки live/vod/series, URL стримов
   собираются из данных аккаунта (`/live/user/pass/id.m3u8|ts`, `/movie/…`,
   `/series/…`). EPG-источник `xmltv.php` создаётся автоматически.

### EPG
- `EpgRepository` скачивает XMLTV (в т.ч. `.gz`), парсит потоково с окном
  «−48ч … +8 дней», пишет в Room и чистит устаревшее.
- Сопоставление каналов с EPG: `tvg-id` → `epg_channel_id` → точное имя канала.
- Для Xtream при отсутствии XMLTV EPG подтягивается
  `get_simple_data_table&stream_id=…` и кэшируется.

### Плеер
- `PlaybackService` владеет `ExoPlayer` + `MediaSession`; UI получает
  `MediaController` в `PlayerViewModel`.
- Метаданные воспроизведения (mime, заголовок, HTTP-заголовки канала) кладутся
  в `MediaItem.RequestMetadata.extras` и разбираются в `onAddMediaItems`.
- `IptvDataSourceFactory` маршрутизирует схемы: http(s) → OkHttp, udp/rtp →
  `UdpDataSource`, прочее (file/rtmp/…) → `DefaultDataSource`.
- Catch-up URL строит `Catchup` (шаблоны `{utc}`/`{utcend}`/`{duration}`/…,
  режимы `default/append/flussonic/shift`, фолбэк для Xtream `tv_archive`).
- Очередь воспроизведения — глобальный `PlaybackQueue` (StateFlow): экран
  пишет туда список каналов + индекс, `PlayerViewModel` реагирует и играет.
- Синтетические сущности: эпизоды сериалов и catch-up стримы — это
  `ChannelEntity`, создаваемые на лету (в БД не пишутся).

### Родительский контроль
PIN хранится как `PBKDF2-SHA1(salt, 24k итераций)` в DataStore. Успешный ввод
открывает доступ на 10 минут в памяти (`ParentalManager`). Блокируются каналы и
категории (флаги `isLocked`/`isHidden` в БД, редактируются в `EditorScreen`).

### Самообновление приложения
`UpdateRepository` — см. [05-AUTO-UPDATE.md](05-AUTO-UPDATE.md).

### Нумерация версий
`versionCode` (номер сборки) передаётся CI через переменную окружения
`APP_VERSION_CODE` (= номер запуска GitHub Actions), `versionName` — через
`APP_VERSION_NAME`. Локально можно задать `-PAPP_VERSION_CODE/-PAPP_VERSION_NAME`.
Подробности — [03-CI-CD.md](03-CI-CD.md).

## Поток типового сценария «пользователь открыл канал»

1. `ChannelsScreen` → `ChannelsViewModel.playChannel(ch)` →
   `PlaybackQueue.set(visibleList, index)`.
2. `navController.navigate("player")` → `PlayerScreen`.
3. `PlayerViewModel` (живёт в Activity) видит изменение очереди → строит
   `PlayRequest` (url, mime, заголовки) → `MediaItems.build()` →
   `controller.setMediaItem(item, startMs)` + `prepare()` + `play()`.
4. `PlaybackService.onAddMediaItems` доразрешает item (mime/заголовки) и играет.
5. UI-состояние (позиция, буферизация, треки, ошибки) стекает в Compose через
   `PlayerViewModel.ui`.

## Что добавить при развитии (см. roadmap)

См. [07-CONTINUATION.md](07-CONTINUATION.md#roadmap).
