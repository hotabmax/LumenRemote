# Lumen Remote для iOS

Нативное приложение (SwiftUI) и ReplayKit-расширение к тому же relay-протоколу, что и
Android-клиент. Готово к сборке и публикации; порядок выпуска — в
[PRODUCTION.md](PRODUCTION.md).

## Что умеет

| Сценарий | Реализация |
|---|---|
| Смотреть экран Android с iPhone и управлять им | `ViewerView` + `RelayClient` (viewer) |
| Показывать экран iPhone на Android | `BroadcastSampleHandler`, ReplayKit |
| Управлять iOS-устройством | Только `open_url` и `clipboard` — iOS не разрешает инъекцию жестов |
| Смотреть экран Android без приложения | Веб-клиент `server/web` в Safari |

Ограничения iOS отражены в интерфейсе: для iOS-партнёра скрыты кнопки жестов и показано
пояснение про ReplayKit.

## Требования

- macOS с Xcode 15+ (iOS 17+);
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) — проект генерируется из `project.yml`;
- Apple Developer Program для устройства и App Store.

## Сборка

```bash
brew install xcodegen
cd ios
xcodegen generate
open LumenRemote.xcodeproj
```

Или из консоли:

```bash
cd ios
xcodebuild -project LumenRemote.xcodeproj -scheme LumenRemote \
  -destination 'platform=iOS Simulator,name=iPhone 15' build
xcodebuild -project LumenRemote.xcodeproj -scheme LumenRemote \
  -destination 'platform=iOS Simulator,name=iPhone 15' test
```

Структурная проверка без Xcode (работает и в CI на Linux):

```bash
python3 tools/swift_structure_check.py ios
```

## Структура

```
ios/
├── project.yml                       XcodeGen: приложение, расширение, тесты
├── PRODUCTION.md                     выпуск в App Store, подпись, TestFlight
├── LumenRemote/
│   ├── App/
│   │   ├── LumenRemoteApp.swift      точка входа, вкладки, AppSettings
│   │   ├── HostView.swift            код, PIN, запуск ReplayKit
│   │   ├── ViewerView.swift          просмотр/управление, ViewerModel
│   │   ├── SettingsView.swift        relay URL, fps, качество
│   │   └── L10n.swift                локализация ru/en
│   ├── Shared/                       общий код приложения и расширения
│   │   ├── Protocol.swift            сообщения, разбор, политика wss://
│   │   ├── RelayClient.swift         WebSocket-клиент, keepalive, события
│   │   └── SessionSettings.swift     App Group: код, PIN, настройки, команды
│   ├── Broadcast/
│   │   ├── BroadcastSampleHandler.swift  ReplayKit: экран iPhone → relay
│   │   ├── Info.plist
│   │   ├── Broadcast.entitlements
│   │   └── PrivacyInfo.xcprivacy
│   └── Resources/
│       ├── Info.plist                ATS, разрешения, ориентации
│       ├── LumenRemote.entitlements  App Group
│       └── PrivacyInfo.xcprivacy     Privacy manifest
└── LumenRemoteTests/
    └── ProtocolTests.swift           юнит-тесты протокола и политики
```

## Общая схема сеанса

```
Viewer (iOS)                              Host (Android / iOS)
RelayClient(.viewer)  ── join{code,pin} ──▶ relay ──▶ Broadcast / Android host
         ◀── ready, auth_ok, hello, JPEG ──────────────
tap/swipe/key/text/cmd ───────────────────▶ управление (Android)
cmd(open_url|clipboard) ──────────────────▶ очередь команд iOS-приложения
```

Команды `cmd` для iOS складываются расширением в App Group и применяются основным
приложением при активации (`AppSettings.applyPendingCommand`).

## Отладка

- `ws://` разрешён только в Debug-сборке (`WireProtocol.isRelayURLAllowed`); для
  release нужен `wss://`.
- Статус и ошибки подключения показываются в форме просмотра.
- События relay видны в Xcode Console (содержимое кадров не логируется).
- Если расширения нет в системном списке «Повтор экрана» — проверьте bundle id
  `com.lumen.remote.broadcast` и совпадение App Group у обоих таргетов.
