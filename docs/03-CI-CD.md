# 03 — CI/CD: автосборка в GitHub Actions

Файл workflow: [`.github/workflows/android.yml`](../.github/workflows/android.yml)

## Что делает pipeline

**Каждый push в любую ветку** (и ручной запуск `workflow_dispatch`):

1. Чекаут (полная история + теги), JDK 17 (Temurin), Gradle с кэшем, Android SDK.
2. Вычисление версии и номера релиза ([`scripts/version.sh`](../scripts/version.sh));
   для обычных веток — `X.Y.N-dev`.
3. Юнит-тесты (`testDebugUnitTest`).
4. Сборка `assembleDebug` + `assembleRelease` + `bundleRelease`.
5. Проверка подписи релизного APK (`apksigner verify --print-certs`).
6. Артефакты workflow: `IP-TV-Player_<версия>_build<N>_{debug.apk,release.apk,release.aab}`.

**Push в `main` и теги `vMAJOR.MINOR.PATCH`** — всё то же плюс:

7. Генерация `app-update.json` (манифест самообновления, см.
   [05-AUTO-UPDATE.md](05-AUTO-UPDATE.md)) скриптом
   [`scripts/make_update_manifest.py`](../scripts/make_update_manifest.py).
8. `SHA256SUMS.txt` — контрольные суммы публикуемых файлов.
9. Создание **GitHub Release** с тегом `vX.Y.N` и заголовком
   «vX.Y.N — релиз #N (сборка M)» (не draft, не prerelease).
10. Фиксация релиза в репозитории: строка в таблице «История релизов»
    (`CHANGELOG.md`) и `LAST_RELEASE` в `version.properties`
    ([`scripts/record_release.py`](../scripts/record_release.py)), коммит
    `chore(release): … [skip ci]` — он не запускает новую сборку.

Для `workflow_dispatch` релиз публикуется только при включённой галочке
«Опубликовать GitHub Release».

Тег с некорректным форматом (например `v1.2`) уронит сборку с понятной ошибкой —
это защита от опечаток в версии.

## Версии, номера релизов и номера сборок

| Что | Значение | Кто задаёт |
|---|---|---|
| `versionName` | `МАЖОР.МИНОР.<номер релиза>`, напр. `1.0.2` | `version.properties` + `scripts/version.sh` |
| Номер релиза | порядковый номер GitHub Release (= patch) | `scripts/version.sh` по тегам `vX.Y.Z` |
| `versionCode` | номер сборки = `github.run_number` | GitHub Actions |

**`versionCode` APK = `github.run_number`** — сквозной счётчик запусков workflow
в этом репозитории. Свойства:

- каждый артефакт CI имеет уникальный возрастающий номер;
- релизы можно выпускать из любой ветки — номер всё равно растёт;
- `versionName` для релиза = `МАЖОР.МИНОР.<номер релиза>`; для сборок обычных
  веток добавляется суффикс `-dev` (`1.0.3-dev`), такие сборки не публикуются;
- в приложении версия отображается как `1.0.2 (релиз 2 · сборка 57)`;
  `versionCode` также виден в «Настройки → О приложении».

Подробно о схеме и способах выпуска — [04-RELEASE.md](04-RELEASE.md).

⚠️ **Важно**: `run_number` привязан к файлу workflow. Не переименовывайте
`.github/workflows/android.yml` и не пересоздавайте репозиторий — иначе счётчик
начнётся заново и самообновление сломается (versionCode перестанет расти).
Если счётчик всё же сброшен, задайте бОльший стартовый номер вручную через
`APP_VERSION_CODE` в течение нескольких пушей.

## Подпись релизных сборок

Приоритет источников ключа:

1. **Секреты репозитория** (если заданы):
   - `SIGNING_KEYSTORE_BASE64` — keystore, закодированный в base64
     (`base64 -w0 release-signing.p12`);
   - `SIGNING_STORE_TYPE` (опционально, по умолчанию `pkcs12`);
   - `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
   Workflow декодирует ключ в `/tmp/release-signing.p12` и передаёт в Gradle
   через переменные окружения.
2. **Коммит-ключ** `keystore/release-signing.p12` + `keystore/keystore.properties`
   — используется, когда секреты не заданы (текущий режим этого репозитория).

Так как ключ один и тот же, **каждый следующий APK устанавливается поверх
предыдущего** без удаления и потери данных. Управление ключом (бэкап, ротация,
риски) — в [06-SIGNING-KEYS.md](06-SIGNING-KEYS.md).

### Перевод подписи на секреты (рекомендуется для приватных форков)

```bash
base64 -w0 keystore/release-signing.p12 > keystore.b64
gh secret set SIGNING_KEYSTORE_BASE64 < keystore.b64
gh secret set SIGNING_STORE_TYPE      --body "pkcs12"
gh secret set SIGNING_STORE_PASSWORD  --body "<пароль из keystore.properties>"
gh secret set SIGNING_KEY_ALIAS       --body "iptvplayer"
gh secret set SIGNING_KEY_PASSWORD    --body "<пароль из keystore.properties>"
```

После этого можно удалить `keystore/` из репозитория — сборки продолжат
подписываться тем же ключом (секреты приоритетнее).

## Артефакты и кэши

- Артефакты каждого запуска: вкладка **Actions → запуск → Artifacts**
  (хранятся 90 дней).
- Gradle-кэш включён (`gradle/actions/setup-gradle@v4`) — типичная сборка ~3–6 мин.
- `concurrency` отменяет устаревшие запуски в той же ветке.

## Dependabot

[`.github/dependabot.yml`](../.github/dependabot.yml) — еженедельные обновления
зависимостей Gradle (сгруппированы androidx и Kotlin) и GitHub Actions.

## Ветка ci/logs (диагностика падений)

При падении сборки workflow публикует компактный дайджест ошибок (ошибки
компиляции `e:`, упавшие тесты со stack trace, хвост лога) в файл
`ci-logs/run-<N>.md` на ветке **ci/logs**. Ветка создаётся с нуля и содержит
**только каталог `ci-logs/`** (никакой копии исходников), каждый новый дайджест
перезаписывает её force-push'ем. Это позволяет читать причину падения через
REST API / веб-интерфейс даже без доступа к полным логам Actions. Изменения в
`ci-logs/**` исключены из триггеров (`paths-ignore`), поэтому публикация
дайджеста не запускает новую сборку. Ветку можно удалить — она пересоздастся
при следующем падении.
