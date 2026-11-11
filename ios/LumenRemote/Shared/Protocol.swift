import Foundation

/// Общие настройки таргетов (App Group для обмена кодом, PIN и настройками).
enum SharedConfig {
    static let appGroup = "group.com.lumen.remote"
    static let appBundleId = "com.lumen.remote"
    static let broadcastBundleId = "com.lumen.remote.broadcast"
}

/// Сообщение `hello`: параметры устройства и его возможности.
struct HelloMessage {
    let name: String
    let platform: String
    let model: String
    let width: Int
    let height: Int
    let control: Bool
    let pinRequired: Bool

    var json: [String: Any] {
        [
            "t": "hello",
            "name": name,
            "platform": platform,
            "model": model,
            "w": width,
            "h": height,
            "control": control,
            "pin": pinRequired,
        ]
    }
}

/// Команда, не требующая инъекции жестов (работает на iOS).
struct CommandMessage {
    let action: String
    let value: String

    static let openURL = "open_url"
    static let clipboard = "clipboard"
}

/// Сборка и разбор сообщений протокола Lumen Remote.
enum WireProtocol {

    // Типы сообщений
    static let auth = "auth"
    static let authOK = "auth_ok"
    static let authFail = "auth_fail"
    static let tap = "tap"
    static let swipe = "swipe"
    static let key = "key"
    static let text = "text"
    static let command = "cmd"
    static let quality = "quality"
    static let ping = "ping"
    static let pong = "pong"
    static let bye = "bye"
    static let peerLeft = "peer_left"

    // Клавиши удалённого устройства
    static let keyBack = "back"
    static let keyHome = "home"
    static let keyRecents = "recents"

    static let minFps = 4
    static let maxFps = 24

    // MARK: Сборка

    static func auth(pin: String) -> [String: Any] {
        ["t": auth, "pin": pin]
    }

    static func tap(x: Double, y: Double) -> [String: Any] {
        ["t": tap, "x": x, "y": y]
    }

    static func swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int) -> [String: Any] {
        ["t": swipe, "x1": x1, "y1": y1, "x2": x2, "y2": y2, "ms": ms]
    }

    static func key(_ value: String) -> [String: Any] {
        ["t": key, "k": value]
    }

    static func text(_ value: String) -> [String: Any] {
        ["t": text, "s": value]
    }

    static func command(action: String, value: String) -> [String: Any] {
        ["t": command, "action": action, "value": value]
    }

    static func quality(fps: Int, jpegQuality: Double) -> [String: Any] {
        ["t": quality, "fps": fps, "q": Int((jpegQuality * 100).rounded())]
    }

    static func ping(timestamp: Int) -> [String: Any] {
        ["t": ping, "ts": timestamp]
    }

    static func pong(timestamp: Int) -> [String: Any] {
        ["t": pong, "ts": timestamp]
    }

    static func bye() -> [String: Any] {
        ["t": bye]
    }

    // MARK: Разбор

    static func type(of json: [String: Any]) -> String {
        json["t"] as? String ?? ""
    }

    static func parseHello(_ json: [String: Any]) -> HelloMessage {
        HelloMessage(
            name: nonEmpty(json["name"] as? String) ?? "Устройство",
            platform: nonEmpty(json["platform"] as? String) ?? "Android",
            model: nonEmpty(json["model"] as? String) ?? "Android",
            width: json["w"] as? Int ?? 0,
            height: json["h"] as? Int ?? 0,
            control: json["control"] as? Bool ?? false,
            pinRequired: json["pin"] as? Bool ?? true
        )
    }

    static func parseCommand(_ json: [String: Any]) -> CommandMessage? {
        guard let action = nonEmpty(json["action"] as? String) else { return nil }
        return CommandMessage(action: action, value: json["value"] as? String ?? "")
    }

    static func parsePin(_ json: [String: Any]) -> String {
        json["pin"] as? String ?? ""
    }

    static func parseTimestamp(_ json: [String: Any]) -> Int {
        if let value = json["ts"] as? Int { return value }
        if let value = json["ts"] as? Double { return Int(value) }
        return 0
    }

    static func parseQuality(_ json: [String: Any], defaultFps: Int, defaultQuality: Double)
        -> (fps: Int, quality: Double) {
        let fps = min(max(json["fps"] as? Int ?? defaultFps, minFps), maxFps)
        let rawQuality = json["q"] as? Int ?? Int(defaultQuality * 100)
        let quality = Double(min(max(rawQuality, 30), 90)) / 100.0
        return (fps, quality)
    }

    /// Разрешён ли адрес relay-сервера.
    ///
    /// В release допускается только `wss://` (как и на Android), в debug —
    /// ещё и `ws://` для локальных тестов.
    static func isRelayURLAllowed(_ rawValue: String) -> Bool {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = value.lowercased()
        guard lowercased.hasPrefix("ws://") || lowercased.hasPrefix("wss://") else {
            return false
        }
        #if DEBUG
        return true
        #else
        return lowercased.hasPrefix("wss://")
        #endif
    }

    static func isNineDigitCode(_ value: String) -> Bool {
        let digits = value.filter { $0.isNumber }
        return digits.count == 9
    }

    static func isFourDigitPin(_ value: String) -> Bool {
        value.count == 4 && value.allSatisfy { $0.isNumber }
    }

    static func formattedCode(_ value: String) -> String {
        let digits = value.filter { $0.isNumber }
        guard digits.count == 9 else { return value }
        let first = digits.prefix(3)
        let middle = digits.dropFirst(3).prefix(3)
        let last = digits.suffix(3)
        return "\(first) \(middle) \(last)"
    }

    static func newCode() -> String {
        String(Int.random(in: 100_000_000...999_999_999))
    }

    static func newPin() -> String {
        String(format: "%04d", Int.random(in: 0...9999))
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
