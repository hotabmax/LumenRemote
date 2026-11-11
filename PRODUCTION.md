# Lumen Remote — продакшн-runbook

Документ описывает подготовку, выпуск и эксплуатацию версии 1.0.0.
Техническое описание приложения — в [README.md](README.md), эксплуатация relay-сервера —
в [server/README.md](server/README.md).

## 1. Что уже настроено в репозитории

| Область | Состояние |
|---|---|
| Release-сборка | R8/минификация, `isDebuggable=false`, `versionName=1.0.0`, `versionCode=1` |
| Debug-сборка | `applicationIdSuffix=.debug`, локальный `ws://` разрешён |
| Подпись | `keystore.properties` (в git не попадает), шаблон — `keystore.properties.example` |
| Манифест | `allowBackup=false`, `usesCleartextTraffic=false` (debug-оверлей разрешает `ws://`) |
| Транспорт | Политика `wss://` в release: `core/BuildFlags.kt` |
| Артефакты | `app-release.apk` и `app-release.aab` собираются одной командой |
| Сервер | Переменные окружения, лимиты, Origin-фильтр, антиперебор PIN, JSON-логи, graceful shutdown |
| Деплой | `server/Dockerfile`, `docker-compose.yml` (relay + Caddy TLS), systemd-юнит |
| CI | `.github/workflows/ci.yml`: Android (build/tests/lint), relay, web-клиент, Python-линт |

## 2. Выпуск приложения

### 2.1. Одноразовая подготовка ключа

```bash
keytool -genkeypair -v -keystore lumen-release.jks \
  -alias lumen -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Lumen Remote, O=Your Company, C=RU"

cp keystore.properties.example keystore.properties      # вне git
# заполните storeFile / storePassword / keyAlias / keyPassword
```

Ключ и пароли храните в менеджере секретов (1Password, Vault, CI secrets).
**Потеря ключа = невозможность обновлять приложение в Google Play.**

В этом рабочем каталоге уже сгенерирован локальный релизный ключ
`keystore/lumen-release.jks` и `keystore.properties` (оба в `.gitignore`),
поэтому `./gradlew assembleRelease` сразу даёт **подписанный** APK и AAB.
Для публикации в Play замените его своим ключом (и сохраните в тайнике):
локальный ключ создан для сборки/проверки и при переустановке ОС будет потерян.

### 2.2. Версия

`app/build.gradle.kts`: поднимайте `versionCode` (+1) и `versionName` (SemVer) на каждый выпуск,
фиксируйте изменения в `CHANGELOG.md`.

### 2.3. Сборка

```bash
./gradlew clean
./gradlew testDebugUnitTest          # юнит-тесты
./gradlew lintRelease                # статический анализ
./gradlew assembleRelease bundleRelease
```

Артефакты:

- `app/build/outputs/apk/release/app-release.apk` — для прямой установки;
- `app/build/outputs/bundle/release/app-release.aab` — для Google Play;
- `app/build/outputs/mapping/release/mapping.txt` — **загрузить в Play Console**
  (нужен для символизации крэшей).

Проверка подписи:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 2.4. Публикация

1. Play Console → Internal testing → загрузить AAB, загрузить `mapping.txt`.
2. Заполнить Data safety: приложение передаёт экран и события управления между устройствами
   по инициативе пользователя; данные не продаются и не используются для рекламы.
3. Указать ограничения: удалённое управление требует явного разрешения пользователя,
   служба доступности используется только во время сеанса.
4. Пройти предупреждения Play о чувствительных разрешениях
   (`AccessibilityService`, `MediaProjection`) — приложить видео-демонстрацию.
5. Выпустить в Internal → Closed → Production по результатам проверки.

## 3. iOS-приложение

Подробный runbook — [`ios/PRODUCTION.md`](ios/PRODUCTION.md). Кратко:

```bash
brew install xcodegen
cd ios && xcodegen generate
xcodebuild -project LumenRemote.xcodeproj -scheme LumenRemote \
  -destination 'platform=iOS Simulator,name=iPhone 15' test
```

Обязательные шаги в Apple Developer / App Store Connect:

1. App ID `com.lumen.remote` и расширение `com.lumen.remote.broadcast`.
2. App Group `group.com.lumen.remote` включён у обоих App ID.
3. Подпись: `DEVELOPMENT_TEAM=<Team ID>` + `-allowProvisioningUpdates`.
4. Версия: `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` в `ios/project.yml`.
5. App Review Notes: ReplayKit-трансляция запускается пользователем, удалённые
   касания на iOS недоступны, relay-сервер задаёт сам пользователь (`wss://`).
6. App Privacy: данные не собираются для трекинга; `PrivacyInfo.xcprivacy` в комплекте.

## 4. Развёртывание relay-сервера

```bash
cd server
cp .env.example .env
# RELAY_DOMAIN=relay.example.com
# ALLOWED_ORIGINS=https://relay.example.com
docker compose up -d --build
curl -fsS https://relay.example.com/health
```

Адрес `wss://relay.example.com/ws` указывается в **Настройки → Relay-сервер**
(в release допускается только `wss://`).

Обязательный минимум для продакшена:

- TLS-сертификат (Caddy получает автоматически);
- `ALLOWED_ORIGINS` = домен веб-клиента;
- `TRUST_PROXY=1` за прокси;
- лимиты `MAX_ROOMS`, `MAX_PAYLOAD_BYTES`, `JOIN_MAX_ATTEMPTS` под нагрузку;
- мониторинг `/health` и `/ready`, сбор логов (JSON) в централизованное хранилище.

## 5. Чек-лист перед выпуском

- [ ] `./gradlew testDebugUnitTest lintRelease` — без ошибок
- [ ] `./gradlew assembleRelease bundleRelease` — успешно
- [ ] Подпись проверена `apksigner verify`
- [ ] Ручной smoke-тест release-сборки: LAN-подключение, relay по `wss://`,
      захват экрана, управление, снимок экрана
- [ ] `versionCode`/`versionName` подняты, `CHANGELOG.md` обновлён
- [ ] `mapping.txt` сохранён для символизации
- [ ] Relay `/health` и `/ready` отвечают, TLS валиден, `ALLOWED_ORIGINS` задан
- [ ] Резервное копирование конфигурации сервера и ключей
- [ ] Инструкция для пользователей (код, PIN, разрешения) опубликована

## 6. Эксплуатация и инциденты

| Инцидент | Действия |
|---|---|
| Relay недоступен | Проверить `/health`, контейнер (`docker compose ps`, логи), TLS; перезапустить `docker compose up -d` |
| Рост `viewer_wrong_pin` | Возможен перебор: снизить `JOIN_MAX_ATTEMPTS`, включить алерт, проверить IP в логах |
| `rooms_limit_reached` | Поднять `MAX_ROOMS`, проверить утечки комнат (`/ready`), перезапустить сервер в окно низкой нагрузки |
| Жалобы на перегрев/трафик | Снизить fps и качество в настройках приложения, ограничить `MAX_PAYLOAD_BYTES` |
| Утечка ключа подписи | Немедленно отозвать ключ в Play Console (App signing key rotation), выпустить обновление |
| Крэши после релиза | Загрузить `mapping.txt`, разобрать стек в Play Console, выпустить hotfix с новым `versionCode` |

## 7. Приватность и соответствие требованиям

- Приложение не отправляет данные на сторонние сервисы: только выбранный пользователем
  relay-сервер и прямой канал в локальной сети.
- Экран и события управления передаются только во время явно запущенного сеанса;
  на принимающей стороне всегда отображается уведомление.
- PIN обязателен по умолчанию; неверный PIN закрывает соединение.
- Для политики конфиденциальности укажите: какие данные передаются (изображение экрана,
  события управления, текст, вводимый пользователем), срок хранения (не хранятся),
  кому передаются (второе устройство пользователя и relay-сервер).
- iOS-ограничения (нет инъекции жестов, трансляция запускается вручную) отражены
  в интерфейсе и документации.

## 8. Откат

- Приложение: вернуть предыдущий AAB в Play Console (Staged rollback) либо остановить
  поэтапный выпуск.
- Сервер: `git revert` + `docker compose up -d --build`; конфигурация — в `.env`,
  состояние в памяти (комнаты) не персистентно, поэтому откат безопасен.
- Мониторинг после отката: `/ready`, доля ошибок входа, жалобы пользователей.
