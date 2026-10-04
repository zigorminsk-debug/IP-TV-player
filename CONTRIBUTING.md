# Contributing / Участие

PR приветствуются. Коротко:

1. Сделайте форк и ветку `feat/…` или `fix/…`.
2. Соберите и прогоните тесты: `./gradlew testDebugUnitTest assembleDebug`.
3. UI-строки — в `values/strings.xml` (EN) **и** `values-ru/strings.xml` (RU).
4. Изменения парсеров/URL-шаблонов сопровождайте тестами
   (`app/src/test/java/com/iptvplayer/app/`).
5. Обновите `CHANGELOG.md` (секция `[Unreleased]`).
6. Не меняйте: `applicationId`, схему `app-update.json`, формат тегов
   `vX.Y.Z`, имя workflow-файла (см. docs/07-CONTINUATION.md — «Критические
   точки»).
7. Опишите PR: что и почему; скриншоты для UI-изменений.

Стиль: официальный Kotlin style (`ktlint`-совместимый), один экран — один пакет
в `ui/screens/…`, логика — в `data/repo/…`.

Релизы мейнтейнер делает тегами — см. [docs/04-RELEASE.md](docs/04-RELEASE.md).
