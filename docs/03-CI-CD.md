# 03 — CI/CD: автосборка в GitHub Actions

Файл workflow: [`.github/workflows/android.yml`](../.github/workflows/android.yml)

## Что делает pipeline

**Каждый push в любую ветку** (и ручной запуск `workflow_dispatch`):

1. Чекаут, JDK 17 (Temurin), Gradle с кэшем, Android SDK.
2. Юнит-тесты (`testDebugUnitTest`).
3. Сборка `assembleDebug` + `assembleRelease` + `bundleRelease`.
4. Проверка подписи релизного APK (`apksigner verify --print-certs`).
5. Артефакты workflow: `IP-TV-Player_<версия>_build<N>_{debug.apk,release.apk,release.aab}`.

**Каждый тег `vMAJOR.MINOR.PATCH`** — всё то же плюс:

6. Генерация `app-update.json` (манифест самообновления, см.
   [05-AUTO-UPDATE.md](05-AUTO-UPDATE.md)) скриптом
   [`scripts/make_update_manifest.py`](../scripts/make_update_manifest.py).
7. `SHA256SUMS.txt` — контрольные суммы всех артефактов.
8. Автоматическое создание **GitHub Release** (не draft, не prerelease) со всеми
   файлами и автосгенерированными примечаниями к релизу.

Тег с некорректным форматом (например `v1.2`) уронит сборку с понятной ошибкой —
это защита от опечаток в версии.

## Номера сборок (build numbers)

**`versionCode` APK = `github.run_number`** — сквозной счётчик запусков workflow
в этом репозитории. Свойства:

- каждый артефакт CI имеет уникальный возрастающий номер;
- релизы можно выпускать из любой ветки — номер всё равно растёт;
- `versionName` берётся из тега (`v1.2.3` → `1.2.3`) или задаётся вручную при
  `workflow_dispatch` (для не-релизных сборок по умолчанию `1.0.0-dev`);
- в приложении версия отображается как `1.2.3 (сборка 57)`; `versionCode`
  также виден в «Настройки → О приложении».

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
`ci-logs/run-<N>.md` на ветке **ci/logs** (force-push, ветка перезаписывается).
Это позволяет читать причину падения через REST API / веб-интерфейс даже без
доступа к полным логам Actions. Изменения в `ci-logs/**` намеренно исключены
из триггеров (`paths-ignore`), чтобы публикация дайджеста не запускала новую
сборку. Ветку можно удалить — она пересоздастся при следующем падении.
