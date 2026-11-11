# iOS: продакшн и App Store

Runbook выпуска iOS-приложения Lumen Remote. Общая подготовка проекта —
в [корневом PRODUCTION.md](../PRODUCTION.md), архитектура — в [README.md](README.md).

## 1. Что уже готово в репозитории

| Область | Состояние |
|---|---|
| Проект | `project.yml` (XcodeGen): app + broadcast extension + unit-тесты, iOS 17+ |
| Версия | `MARKETING_VERSION=1.0.0`, `CURRENT_PROJECT_VERSION=1` в `project.yml` |
| Подпись | Automatic signing, `DEVELOPMENT_TEAM` подставляется при выпуске |
| App Group | `group.com.lumen.remote` в entitlements обоих таргетов |
| Инфо-плей | ATS без исключений (только `wss://`), `ITSAppUsesNonExemptEncryption=false` |
| Приватность | `PrivacyInfo.xcprivacy` (UserDefaults CA92.1, без трекинга) |
| Тесты | `LumenRemoteTests/ProtocolTests.swift` |
| CI | `.github/workflows/ios.yml` (macOS-раннер: xcodegen + xcodebuild test) |

## 2. Подготовка в Apple Developer / App Store Connect

1. **Bundle ID** для приложения: `com.lumen.remote` (Identifiers → App IDs).
2. **Bundle ID** для расширения: `com.lumen.remote.broadcast`.
3. **App Group**: `group.com.lumen.remote`; включите его в Capabilities **обоих**
   App ID (App Groups) — иначе расширение не сможет передавать код/PIN.
4. Создайте приложение в App Store Connect с тем же Bundle ID.
5. Укажите `DEVELOPMENT_TEAM` (Team ID) при выпуске:
   ```bash
   cd ios && xcodegen generate
   xcodebuild -project LumenRemote.xcodeproj -scheme LumenRemote \
     DEVELOPMENT_TEAM=XXXXXXXXXX -allowProvisioningUpdates archive \
     -archivePath build/LumenRemote.xcarchive
   ```
   Либо задайте Team в Xcode (Signing & Capabilities) один раз.

## 3. Версия и сборка

```bash
cd ios
xcodegen generate

# 1. Поднять версию в project.yml:
#    MARKETING_VERSION: 1.0.1
#    CURRENT_PROJECT_VERSION: 2

xcodebuild -project LumenRemote.xcodeproj -scheme LumenRemote \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath build/LumenRemote.xcarchive archive

xcodebuild -exportArchive \
  -archivePath build/LumenRemote.xcarchive \
  -exportOptionsPlist ExportOptions.plist \
  -exportPath build/ipa
```

`ExportOptions.plist` (пример для App Store):

```xml
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>export</string>
  <key>uploadSymbols</key><true/>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
```

Загрузка: `xcrun altool --upload-app -f build/ipa/LumenRemote.ipa -t ios \
--apiKey <KEY_ID> --apiIssuer <ISSUER_ID>` (или Transporter / Xcode Organizer).

## 4. TestFlight и ревью

1. Internal Testing → проверить: приём (ReplayKit), просмотр Android, команды
   ссылки/буфера, снимок экрана, поведение при обрыве сети.
2. **App Review Notes** — обязательно опишите:
   - приложение передаёт экран и события управления только по явному действию
     пользователя (кнопка «Начать трансляцию» → системное окно ReplayKit);
   - на iOS нет удалённого управления касаниями: доступны просмотр, ссылки,
     буфер обмена — это ограничение платформы, а не урезанная функциональность;
   - relay-сервер пользователь указывает сам (`wss://`), данные не продаются;
   - расширение ReplayKit нужно для трансляции экрана на устройство партнёра.
3. **App Privacy** (nutrition label): данные не собираются для трекинга; экран,
   текст и команды передаются между устройствами пользователя и не хранятся.
4. Приложите демо-видео (трансляция + подключение) — ускоряет ревью ReplayKit-расширения.

## 5. Чек-лист выпуска

- [ ] `python3 tools/swift_structure_check.py ios` — без проблем
- [ ] `xcodebuild ... test` — тесты зелёные
- [ ] Версии подняты (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`)
- [ ] App Group включён у обоих App ID, entitlements совпадают
- [ ] Release-сборка: адрес relay только `wss://`, соединение устанавливается
- [ ] Трансляция: экран появляется на Android-устройстве, команды `open_url`/`clipboard` работают
- [ ] Снимок экрана сохраняется в Фото (разрешение запрашивается)
- [ ] Privacy manifest внутри `.ipa` (`PrivacyInfo.xcprivacy`)
- [ ] App Review Notes и демо-видео приложены
- [ ] `mapping`-символы не требуются (iOS), dSYM загружаются автоматически при upload symbols

## 6. Диагностика

| Симптом | Причина / решение |
|---|---|
| Расширения нет в списке «Повтор экрана» | bundle id расширения не `com.lumen.remote.broadcast` или не встроено в приложение |
| Трансляция запускается и сразу падает | не задан `relay_url`/`wss://` в настройках приложения; проверьте App Group |
| Android-клиент не видит комнату | приложение показывает код/PIN из другого App Group контейнера |
| `tls_error` при подключении | сертификат сервера недоверенный или адрес `ws://` в release |
| Не сохраняется снимок | пользователь отклонил доступ к добавлению фото |
| Соединение рвётся в фоне | iOS ограничивает сеть в фоне; трансляция работает через ReplayKit-расширение |

## 7. Откат

- Остановите поэтапный выпуск (Phased Release → Pause) или отклоните версию в App Store Connect.
- Предыдущая версия остаётся доступной; для отката поведения сервера используйте
  общий runbook (`PRODUCTION.md` §7).
