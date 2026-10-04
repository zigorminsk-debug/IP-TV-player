# 02 — Локальная сборка

## Требования

| Инструмент | Версия |
|---|---|
| JDK | 17 (Temurin/Adoptium и т.п.) |
| Android SDK | platform 35, build-tools (ставит Gradle сам при принятых лицензиях) |
| Gradle | не нужен отдельно — используется `./gradlew` (wrapper 8.10.2) |
| ОС | Linux / macOS / Windows |

Проверка JDK: `java -version` → должно быть `17.x` (или новее, но компиляция
настроена на target 17).

## Команды

```bash
# отладочная сборка
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# релизная сборка (подписывается ключом из keystore/, см. docs/06-SIGNING-KEYS.md)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# AAB (для загрузки в сторы)
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab

# юнит-тесты
./gradlew testDebugUnitTest

# установить на подключённое устройство
./gradlew installDebug
```

## Нумерация версий локально

По умолчанию (`app/build.gradle.kts`): `versionCode = 1`,
`versionName = "1.0.0-dev"`. Можно задать явно:

```bash
./gradlew assembleRelease -PAPP_VERSION_CODE=123 -PAPP_VERSION_NAME=1.2.3
# или через переменные окружения:
APP_VERSION_CODE=123 APP_VERSION_NAME=1.2.3 ./gradlew assembleRelease
```

Приоритет: переменные окружения → gradle-свойства → значения по умолчанию.
**CI всегда передаёт `APP_VERSION_CODE` = номер запуска workflow** — так каждый
артефакт получает уникальный номер сборки (см. [03-CI-CD.md](03-CI-CD.md)).

## Подпись

- Релизная сборка ищет ключ так: переменные окружения
  `SIGNING_STORE_FILE/…PASSWORD/…ALIAS` → `keystore/keystore.properties`
  (положен в репозиторий) → при их отсутствии fallback на debug-ключ.
- Подробнее о ключе, его бэкапе и ротации: [06-SIGNING-KEYS.md](06-SIGNING-KEYS.md).

## Android Studio

1. `File → Open…` → выбрать каталог проекта.
2. Дождаться Gradle Sync.
3. `Run ▶` — установить debug-сборку на устройство/эмулятор.

## Частые проблемы

| Проблема | Решение |
|---|---|
| `SDK location not found` | Создайте `local.properties` со строкой `sdk.dir=/путь/к/Android/sdk` (не коммитится) |
| `Failed to install SDK components` / лицензии | `sdkmanager --licenses` и принять |
| Ошибка `Unsupported class file major version` | Проверьте, что Gradle использует JDK 17 (`./gradlew -version`) |
| Медленная первая сборка | Норма: качаются зависимости и SDK-компоненты |
| OutOfMemory демона | Поднимите `-Xmx` в `gradle.properties` |

## Отличия debug-сборки

`applicationId` у debug — `com.iptvplayer.app.debug` (ставится рядом с релизом),
`versionName` с суффиксом `-debug`. **Самообновление в debug отключено**
(проверяется `packageName`), чтобы debug-сборка не пыталась обновляться.
